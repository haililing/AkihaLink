//go:build linux && with_akihalink_observability && with_ebpf && cgo && (386 || amd64 || arm || arm64)

package akihalinkobs

import (
	"errors"
	"os"
	"path/filepath"

	"github.com/cilium/ebpf"
	"github.com/cilium/ebpf/link"
)

const persistentRedirectRoot = "/sys/fs/bpf/akihalink/generic/v1/redirect"

const redirectOwnerMagic uint64 = 0x414b495245444952

type redirectOwner struct {
	Magic      uint64
	ABIVersion uint64
	Generation uint64
	ConfigID   uint64
}

func cleanupPersistentRedirect() error {
	ownerPath := filepath.Join(persistentRedirectRoot, "owner")
	ownerMap, err := ebpf.LoadPinnedMap(ownerPath, nil)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var key uint32
	var owner redirectOwner
	if err = ownerMap.Lookup(&key, &owner); err != nil {
		ownerMap.Close()
		return err
	}
	ownerMap.Close()
	if owner.Magic != redirectOwnerMagic || owner.ABIVersion == 0 || owner.ABIVersion > 2 {
		return errors.New("refusing to clean unowned redirect BPF pins")
	}
	for _, name := range persistentRedirectPrograms {
		pinned, loadErr := link.LoadPinnedLink(filepath.Join(persistentRedirectRoot, "links", name), nil)
		if loadErr == nil {
			_ = pinned.Unpin()
			_ = pinned.Close()
		}
	}
	for _, directory := range []string{"links", "programs", "maps"} {
		path := filepath.Join(persistentRedirectRoot, directory)
		entries, _ := os.ReadDir(path)
		for _, entry := range entries {
			_ = os.Remove(filepath.Join(path, entry.Name()))
		}
		_ = os.Remove(path)
	}
	_ = os.Remove(ownerPath)
	return os.Remove(persistentRedirectRoot)
}

var persistentRedirectPrograms = []string{
	"connect4", "connect6", "connect6_v4mapped",
	"udp4_sendmsg", "udp6_sendmsg", "udp6_v4mapped_sendmsg",
	"udp4_recvmsg", "udp6_recvmsg", "udp6_v4mapped_recvmsg",
}

func CleanupPersistentRedirect() error {
	return cleanupPersistentRedirect()
}
