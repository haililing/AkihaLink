//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestMigrateV14Config(t *testing.T) {
	path := filepath.Join(t.TempDir(), "current.json")
	legacy := `{"inbounds":[{"type":"ebpf","tag":"ebpf-in","network":["tcp","udp"],"dns_mode":"hijack","redirect_address":["127.128.0.0/9","fd53:696e:672d:626f::/64"],"include_uid":[1051],"include_uid_range":[],"exclude_uid":[10456],"shared_network":{"enabled":true,"android_tethering":"wifi","tc_priority":1}}]}`
	if err := os.WriteFile(path, []byte(legacy), 0o600); err != nil {
		t.Fatal(err)
	}
	result, err := MigrateV14Config(path)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Migrated || result.Backup == "" {
		t.Fatalf("unexpected migration result: %+v", result)
	}
	var root map[string]any
	payload, _ := os.ReadFile(path)
	if err = json.Unmarshal(payload, &root); err != nil {
		t.Fatal(err)
	}
	inbound := root["inbounds"].([]any)[0].(map[string]any)
	if inbound["mode"] != "hybrid" || inbound["redirect_address"] != nil || inbound["shared_network"] != nil {
		t.Fatalf("legacy fields were not migrated: %v", inbound)
	}
	shared := inbound["shared"].(map[string]any)
	if shared["android_tethering"] != "wifi" {
		t.Fatalf("hotspot migration failed: %v", shared)
	}
	second, err := MigrateV14Config(path)
	if err != nil || second.Migrated {
		t.Fatalf("migration is not idempotent: result=%+v err=%v", second, err)
	}
	backup, _ := os.ReadFile(result.Backup)
	if string(backup) != legacy {
		t.Fatal("v14 backup was not preserved byte-for-byte")
	}
}

func TestMigrateV14ConfigRejectsUnknownShapeWithoutWriting(t *testing.T) {
	path := filepath.Join(t.TempDir(), "current.json")
	legacy := `{"inbounds":[{"type":"ebpf","redirect_address":["127.128.0.0/9","fd53:696e:672d:626f::/64"],"vendor_field":true}]}`
	if err := os.WriteFile(path, []byte(legacy), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := MigrateV14Config(path); err == nil {
		t.Fatal("unknown legacy shape was accepted")
	}
	payload, _ := os.ReadFile(path)
	if string(payload) != legacy {
		t.Fatal("rejected legacy configuration was modified")
	}
}

func TestMigrateV14LocalConfigAndRejectsForeignRedirect(t *testing.T) {
	directory := t.TempDir()
	localPath := filepath.Join(directory, "local.json")
	local := `{"inbounds":[{"type":"ebpf","redirect_address":["fd53:696e:672d:626f::/64","127.128.0.0/9"],"cgroup_enabled":true,"dns_mode":"hijack","exclude_uid":[]} ]}`
	if err := os.WriteFile(localPath, []byte(local), 0o600); err != nil {
		t.Fatal(err)
	}
	result, err := MigrateV14Config(localPath)
	if err != nil || !result.Migrated {
		t.Fatalf("local migration failed: result=%+v err=%v", result, err)
	}
	var root map[string]any
	payload, _ := os.ReadFile(localPath)
	if err = json.Unmarshal(payload, &root); err != nil {
		t.Fatal(err)
	}
	inbound := root["inbounds"].([]any)[0].(map[string]any)
	if inbound["mode"] != "local" || inbound["shared"] != nil {
		t.Fatalf("unexpected local migration: %v", inbound)
	}

	foreignPath := filepath.Join(directory, "foreign.json")
	foreign := `{"inbounds":[{"type":"ebpf","redirect_address":["127.0.0.0/8","fd53:696e:672d:626f::/64"]}]}`
	if err = os.WriteFile(foreignPath, []byte(foreign), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err = MigrateV14Config(foreignPath); err == nil {
		t.Fatal("foreign redirect prefix was accepted")
	}
	unchanged, _ := os.ReadFile(foreignPath)
	if string(unchanged) != foreign {
		t.Fatal("foreign configuration was modified")
	}
}

func TestConfigsEqualExceptUIDExclusions(t *testing.T) {
	directory := t.TempDir()
	left := filepath.Join(directory, "left.json")
	right := filepath.Join(directory, "right.json")
	base := `{"inbounds":[{"type":"ebpf","mode":"local","local":{"dns_mode":"hijack","exclude_uid":[%d]}}],"outbounds":[{"type":"direct"}]}`
	_ = os.WriteFile(left, []byte(fmt.Sprintf(base, 10001)), 0o600)
	_ = os.WriteFile(right, []byte(fmt.Sprintf(base, 10002)), 0o600)
	equal, err := ConfigsEqualExceptUIDExclusions(left, right)
	if err != nil || !equal {
		t.Fatalf("UID-only change was rejected: equal=%t err=%v", equal, err)
	}
	changed := `{"inbounds":[{"type":"ebpf","mode":"local","local":{"dns_mode":"off","exclude_uid":[10002]}}],"outbounds":[{"type":"direct"}]}`
	if err = os.WriteFile(right, []byte(changed), 0o600); err != nil {
		t.Fatal(err)
	}
	equal, err = ConfigsEqualExceptUIDExclusions(left, right)
	if err != nil || equal {
		t.Fatalf("non-UID change was accepted: equal=%t err=%v", equal, err)
	}
}
