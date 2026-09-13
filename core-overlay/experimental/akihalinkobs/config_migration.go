//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
)

var legacyRedirectPrefixes = []string{
	"127.128.0.0/9",
	"fd53:696e:672d:626f::/64",
}

type ConfigMigrationResult struct {
	Migrated bool   `json:"migrated"`
	Backup   string `json:"backup,omitempty"`
}

// MigrateV14Config retains the daemon command's API while migrating both the
// v14 flat layout and the v15/v16 RC1 layout directly to the v17 bundle schema.
// Unknown legacy shapes are left byte-for-byte untouched so the module can
// fail safe and ask the App to apply a fresh configuration.
func MigrateV14Config(path string) (ConfigMigrationResult, error) {
	payload, err := os.ReadFile(path)
	if err != nil {
		return ConfigMigrationResult{}, err
	}
	var root map[string]json.RawMessage
	if err = json.Unmarshal(payload, &root); err != nil {
		return ConfigMigrationResult{}, fmt.Errorf("decode saved configuration: %w", err)
	}
	var inbounds []json.RawMessage
	if err = json.Unmarshal(root["inbounds"], &inbounds); err != nil {
		return ConfigMigrationResult{}, fmt.Errorf("decode saved inbounds: %w", err)
	}
	migrated := false
	for index, rawInbound := range inbounds {
		updated, changed, migrateErr := migrateV14Inbound(rawInbound)
		if migrateErr != nil {
			return ConfigMigrationResult{}, migrateErr
		}
		updated, changedV16, migrateErr := migrateV16Inbound(updated)
		if migrateErr != nil {
			return ConfigMigrationResult{}, migrateErr
		}
		changed = changed || changedV16
		if changed {
			inbounds[index] = updated
			migrated = true
		}
	}
	if !migrated {
		return ConfigMigrationResult{}, nil
	}
	root["inbounds"], err = json.Marshal(inbounds)
	if err != nil {
		return ConfigMigrationResult{}, err
	}
	updated, err := json.Marshal(root)
	if err != nil {
		return ConfigMigrationResult{}, err
	}
	backup := path + ".pre-v17.bak"
	if _, statErr := os.Stat(backup); errors.Is(statErr, os.ErrNotExist) {
		if err = atomicPrivateConfigWrite(backup, payload); err != nil {
			return ConfigMigrationResult{}, fmt.Errorf("write pre-v17 configuration backup: %w", err)
		}
	} else if statErr != nil {
		return ConfigMigrationResult{}, statErr
	}
	if err = atomicPrivateConfigWrite(path, append(updated, '\n')); err != nil {
		return ConfigMigrationResult{}, fmt.Errorf("write migrated configuration: %w", err)
	}
	return ConfigMigrationResult{Migrated: true, Backup: backup}, nil
}

func migrateV14Inbound(rawInbound json.RawMessage) (json.RawMessage, bool, error) {
	var inbound map[string]json.RawMessage
	if err := json.Unmarshal(rawInbound, &inbound); err != nil {
		return nil, false, fmt.Errorf("decode saved inbound: %w", err)
	}
	var inboundType string
	_ = json.Unmarshal(inbound["type"], &inboundType)
	if inboundType != "ebpf" {
		return rawInbound, false, nil
	}
	if _, hasLocal := inbound["local"]; hasLocal {
		if _, hasMode := inbound["mode"]; !hasMode {
			// The new schema has no mode. Strict core decoding still validates it
			// before the module starts, without rewriting current configurations.
			return rawInbound, false, nil
		}
	}
	if _, hasMode := inbound["mode"]; hasMode {
		if _, hasLocal := inbound["local"]; !hasLocal {
			return nil, false, errors.New("saved eBPF configuration has an incomplete v15 schema")
		}
		for _, legacy := range []string{"redirect_address", "cgroup_enabled", "dns_mode", "include_uid", "include_uid_range", "exclude_uid", "exclude_uid_range", "shared_network"} {
			if _, present := inbound[legacy]; present {
				return nil, false, fmt.Errorf("saved eBPF configuration mixes v14 and v15 field %s", legacy)
			}
		}
		return rawInbound, false, nil
	}
	allowed := map[string]struct{}{
		"type": {}, "tag": {}, "network": {}, "udp_timeout": {}, "bypass_rule_set": {},
		"redirect_address": {}, "cgroup_enabled": {}, "dns_mode": {}, "include_uid": {},
		"include_uid_range": {}, "exclude_uid": {}, "exclude_uid_range": {}, "shared_network": {},
	}
	for field := range inbound {
		if _, known := allowed[field]; !known {
			return nil, false, fmt.Errorf("saved v14 eBPF configuration contains unknown field %s", field)
		}
	}
	var prefixes []string
	if err := json.Unmarshal(inbound["redirect_address"], &prefixes); err != nil || !sameStringSet(prefixes, legacyRedirectPrefixes) {
		return nil, false, errors.New("saved v14 eBPF redirect prefixes are not recognized")
	}
	if rawEnabled, present := inbound["cgroup_enabled"]; present {
		var enabled bool
		if err := json.Unmarshal(rawEnabled, &enabled); err != nil || !enabled {
			return nil, false, errors.New("saved v14 eBPF local interception was disabled")
		}
	}
	local := make(map[string]json.RawMessage)
	for _, field := range []string{"dns_mode", "include_uid", "include_uid_range", "exclude_uid", "exclude_uid_range"} {
		if value, present := inbound[field]; present {
			local[field] = value
		}
	}
	if _, present := local["dns_mode"]; !present {
		local["dns_mode"] = json.RawMessage(`"hijack"`)
	}
	for _, field := range []string{"include_uid", "include_uid_range", "exclude_uid"} {
		if _, present := local[field]; !present {
			local[field] = json.RawMessage(`[]`)
		}
	}
	shared, hotspot, err := migrateV14Shared(inbound["shared_network"])
	if err != nil {
		return nil, false, err
	}
	for _, field := range []string{"redirect_address", "cgroup_enabled", "dns_mode", "include_uid", "include_uid_range", "exclude_uid", "exclude_uid_range", "shared_network"} {
		delete(inbound, field)
	}
	inbound["local"], _ = json.Marshal(local)
	if hotspot {
		inbound["mode"] = json.RawMessage(`"hybrid"`)
		inbound["shared"], _ = json.Marshal(shared)
	} else {
		inbound["mode"] = json.RawMessage(`"local"`)
	}
	updated, err := json.Marshal(inbound)
	return updated, true, err
}

func migrateV14Shared(raw json.RawMessage) (map[string]any, bool, error) {
	if len(raw) == 0 || bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return nil, false, nil
	}
	var legacy map[string]json.RawMessage
	if err := json.Unmarshal(raw, &legacy); err != nil {
		return nil, false, errors.New("decode saved v14 shared-network configuration")
	}
	for field := range legacy {
		if field != "enabled" && field != "android_tethering" && field != "tc_priority" {
			return nil, false, fmt.Errorf("saved v14 shared-network configuration contains unknown field %s", field)
		}
	}
	var enabled bool
	if err := json.Unmarshal(legacy["enabled"], &enabled); err != nil || !enabled {
		return nil, false, errors.New("saved v14 shared-network configuration is not enabled")
	}
	var tethering string
	if err := json.Unmarshal(legacy["android_tethering"], &tethering); err != nil || tethering != "wifi" {
		return nil, false, errors.New("saved v14 shared-network configuration is not Android Wi-Fi tethering")
	}
	priority := 1
	if rawPriority, present := legacy["tc_priority"]; present {
		if err := json.Unmarshal(rawPriority, &priority); err != nil || priority != 1 {
			return nil, false, errors.New("saved v14 shared-network tc_priority is not recognized")
		}
	}
	return map[string]any{
		"dns_mode":          "hijack",
		"android_tethering": "wifi",
		"advanced": map[string]any{
			"tc_priority": priority,
			"data_plane":  "rewrite",
		},
	}, true, nil
}

// Only migrate layouts emitted by released AkihaLink generators. Unknown
// performance or routing overrides must be reapplied by the App explicitly.
func migrateV16Inbound(raw json.RawMessage) (json.RawMessage, bool, error) {
	var inbound map[string]json.RawMessage
	if err := json.Unmarshal(raw, &inbound); err != nil {
		return nil, false, err
	}
	var kind, mode string
	_ = json.Unmarshal(inbound["type"], &kind)
	if kind != "ebpf" || inbound["mode"] == nil {
		return raw, false, nil
	}
	if err := json.Unmarshal(inbound["mode"], &mode); err != nil || (mode != "local" && mode != "hybrid") {
		return nil, false, errors.New("saved eBPF mode is not an AkihaLink local/hybrid configuration")
	}
	if err := requireMigrationFields(inbound, "type", "tag", "network", "udp_timeout", "bypass_rule_set", "mode", "local", "shared", "tcp_splice"); err != nil {
		return nil, false, err
	}
	if splice, present := inbound["tcp_splice"]; present && !bytes.Equal(bytes.TrimSpace(splice), []byte("false")) {
		return nil, false, errors.New("saved tcp_splice is unsupported by the new core")
	}
	var local map[string]json.RawMessage
	if err := json.Unmarshal(inbound["local"], &local); err != nil || local == nil {
		return nil, false, errors.New("saved eBPF local options are missing")
	}
	if err := requireMigrationFields(local, "dns_mode", "cgroup_path", "ipv6_mode", "bypass_private_address", "include_uid", "include_uid_range", "exclude_uid", "exclude_uid_range", "include_android_user", "include_package", "exclude_package"); err != nil {
		return nil, false, err
	}
	if err := migrateIPv6Option(local, true); err != nil {
		return nil, false, err
	}
	local["enabled"] = json.RawMessage(`true`)
	local["data_plane"] = json.RawMessage(`"cgroup"`)
	inbound["local"], _ = json.Marshal(local)
	inbound["tc_priority"] = json.RawMessage(`1`)
	if mode == "hybrid" {
		var shared map[string]json.RawMessage
		if err := json.Unmarshal(inbound["shared"], &shared); err != nil || shared == nil {
			return nil, false, errors.New("saved hybrid eBPF shared options are missing")
		}
		if err := requireMigrationFields(shared, "dns_mode", "android_tethering", "ipv6_mode", "bypass_private_address", "advanced"); err != nil {
			return nil, false, err
		}
		var tethering string
		if err := json.Unmarshal(shared["android_tethering"], &tethering); err != nil || tethering != "wifi" {
			return nil, false, errors.New("saved hotspot must use Android Wi-Fi discovery")
		}
		var advanced map[string]json.RawMessage
		if err := json.Unmarshal(shared["advanced"], &advanced); err != nil || advanced == nil {
			return nil, false, errors.New("saved hotspot advanced options are missing")
		}
		if err := requireMigrationFields(advanced, "tc_priority", "data_plane"); err != nil {
			return nil, false, err
		}
		var priority int
		var dataPlane string
		if json.Unmarshal(advanced["tc_priority"], &priority) != nil || priority != 1 ||
			json.Unmarshal(advanced["data_plane"], &dataPlane) != nil || dataPlane != "rewrite" {
			return nil, false, errors.New("saved hotspot data plane or priority is not recognized")
		}
		if err := migrateIPv6Option(shared, false); err != nil {
			return nil, false, err
		}
		delete(shared, "advanced")
		shared["enabled"] = json.RawMessage(`true`)
		shared["data_plane"] = json.RawMessage(`"packet_rewrite"`)
		inbound["shared"], _ = json.Marshal(shared)
	} else if _, present := inbound["shared"]; present {
		return nil, false, errors.New("saved local eBPF configuration unexpectedly contains shared options")
	}
	delete(inbound, "mode")
	delete(inbound, "tcp_splice")
	updated, err := json.Marshal(inbound)
	return updated, true, err
}

func requireMigrationFields(object map[string]json.RawMessage, allowed ...string) error {
	for field := range object {
		if !slices.Contains(allowed, field) {
			return fmt.Errorf("saved eBPF configuration contains unsupported field %s", field)
		}
	}
	return nil
}

func migrateIPv6Option(options map[string]json.RawMessage, local bool) error {
	raw, present := options["ipv6_mode"]
	if !present {
		return nil
	}
	var mode string
	if err := json.Unmarshal(raw, &mode); err != nil {
		return errors.New("saved ipv6_mode is invalid")
	}
	switch mode {
	case "off":
		options["ipv6"] = json.RawMessage(`false`)
	case "always":
		options["ipv6"] = json.RawMessage(`true`)
	case "auto":
		if !local {
			return errors.New("saved shared ipv6_mode=auto is invalid")
		}
		// 1.15 enables IPv6 by default and owns runtime address handling.
	default:
		return fmt.Errorf("saved ipv6_mode %q is not recognized", mode)
	}
	delete(options, "ipv6_mode")
	return nil
}

func ConfigsEqualExceptUIDExclusions(leftPath, rightPath string) (bool, error) {
	left, err := normalizedConfigWithoutUIDExclusions(leftPath)
	if err != nil {
		return false, err
	}
	right, err := normalizedConfigWithoutUIDExclusions(rightPath)
	if err != nil {
		return false, err
	}
	return bytes.Equal(left, right), nil
}

func normalizedConfigWithoutUIDExclusions(path string) ([]byte, error) {
	payload, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var root map[string]any
	if err = json.Unmarshal(payload, &root); err != nil {
		return nil, err
	}
	inbounds, loaded := root["inbounds"].([]any)
	if !loaded {
		return nil, errors.New("configuration does not contain inbounds")
	}
	ebpfCount := 0
	for _, value := range inbounds {
		inbound, object := value.(map[string]any)
		if !object || inbound["type"] != "ebpf" {
			continue
		}
		ebpfCount++
		local, object := inbound["local"].(map[string]any)
		if !object {
			return nil, errors.New("eBPF inbound does not use the v15 local schema")
		}
		delete(local, "exclude_uid")
		delete(local, "exclude_uid_range")
	}
	if ebpfCount != 1 {
		return nil, errors.New("configuration must contain exactly one eBPF inbound")
	}
	return json.Marshal(root)
}

func sameStringSet(left, right []string) bool {
	left = slices.Clone(left)
	right = slices.Clone(right)
	slices.Sort(left)
	slices.Sort(right)
	return slices.Equal(left, right)
}

func atomicPrivateConfigWrite(path string, content []byte) error {
	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(directory, ".akihalink-config-*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err = temporary.Chmod(0o600); err == nil {
		_, err = temporary.Write(content)
	}
	if syncErr := temporary.Sync(); err == nil {
		err = syncErr
	}
	if closeErr := temporary.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return os.Rename(temporaryPath, path)
}
