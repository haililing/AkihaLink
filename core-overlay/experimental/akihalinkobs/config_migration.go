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

// MigrateV14Config performs the only supported persisted-config migration.
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
	backup := path + ".v14.bak"
	if _, statErr := os.Stat(backup); errors.Is(statErr, os.ErrNotExist) {
		if err = atomicPrivateConfigWrite(backup, payload); err != nil {
			return ConfigMigrationResult{}, fmt.Errorf("write v14 configuration backup: %w", err)
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
