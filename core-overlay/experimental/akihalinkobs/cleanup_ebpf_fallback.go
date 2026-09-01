//go:build linux && with_akihalink_observability && (!with_ebpf || !cgo || (!386 && !amd64 && !arm && !arm64))

package akihalinkobs

func cleanupPersistentRedirect() error {
	return nil
}

func CleanupPersistentRedirect() error {
	return cleanupPersistentRedirect()
}
