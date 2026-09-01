//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/sagernet/netlink"
	"golang.org/x/sys/unix"
)

const (
	sharedIngressFilterHandle = 0x5342
	sharedEgressFilterHandle  = 0x5343
	sharedIngressFilterName   = "sb_share_in"
	sharedEgressFilterName    = "sb_share_out"
)

var safeSharedInterfaceName = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,15}$`)

type sharedNetworkJournal struct {
	InterfaceName         string `json:"interfaceName"`
	InterfaceIndex        int    `json:"interfaceIndex"`
	RouteLocalnetOriginal int    `json:"routeLocalnetOriginal"`
	CreatedClsact         bool   `json:"createdClsact"`
}

// CleanupSharedNetwork removes only TC state recorded by this AkihaLink
// runtime. Both interface name and ifindex must still match before any live
// interface is touched.
func CleanupSharedNetwork(runtimeDirectory string) error {
	runtimeDirectory = filepath.Clean(runtimeDirectory)
	if runtimeDirectory == "." || runtimeDirectory == string(filepath.Separator) || !filepath.IsAbs(runtimeDirectory) {
		return fmt.Errorf("invalid AkihaLink runtime directory")
	}
	directory := filepath.Join(runtimeDirectory, "shared-network")
	entries, err := os.ReadDir(directory)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var cleanupErr error
	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		path := filepath.Join(directory, entry.Name())
		if err = cleanupSharedNetworkJournal(path); err != nil {
			cleanupErr = errors.Join(cleanupErr, fmt.Errorf("cleanup %s: %w", entry.Name(), err))
		}
	}
	if cleanupErr == nil {
		_ = os.Remove(directory)
	}
	return cleanupErr
}

func cleanupSharedNetworkJournal(path string) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	decoder := json.NewDecoder(io.LimitReader(file, 4097))
	decoder.DisallowUnknownFields()
	var record sharedNetworkJournal
	err = decoder.Decode(&record)
	closeErr := file.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil || !safeSharedInterfaceName.MatchString(record.InterfaceName) || record.InterfaceIndex <= 0 ||
		(record.RouteLocalnetOriginal != 0 && record.RouteLocalnetOriginal != 1) {
		return fmt.Errorf("invalid shared-network ownership record")
	}
	link, err := netlink.LinkByName(record.InterfaceName)
	if isMissingSharedLink(err) {
		return os.Remove(path)
	}
	if err != nil {
		return err
	}
	if link.Attrs() == nil || link.Attrs().Index != record.InterfaceIndex {
		return os.Remove(path)
	}
	if err = removeOwnedSharedFilters(link); err != nil {
		return err
	}
	if record.RouteLocalnetOriginal == 0 {
		routePath := "/proc/sys/net/ipv4/conf/" + record.InterfaceName + "/route_localnet"
		value, readErr := os.ReadFile(routePath)
		if readErr != nil && !errors.Is(readErr, os.ErrNotExist) {
			return readErr
		}
		if strings.TrimSpace(string(value)) == "1" {
			if err = os.WriteFile(routePath, []byte("0"), 0o644); err != nil {
				return err
			}
		}
	}
	if record.CreatedClsact {
		if err = removeEmptySharedClsact(link); err != nil {
			return err
		}
	}
	return os.Remove(path)
}

func removeOwnedSharedFilters(link netlink.Link) error {
	for _, owned := range []struct {
		parent uint32
		handle uint16
		name   string
	}{
		{netlink.HANDLE_MIN_INGRESS, sharedIngressFilterHandle, sharedIngressFilterName},
		{netlink.HANDLE_MIN_EGRESS, sharedEgressFilterHandle, sharedEgressFilterName},
	} {
		filters, err := netlink.FilterList(link, owned.parent)
		if err != nil {
			return err
		}
		for _, filter := range filters {
			bpfFilter, ok := filter.(*netlink.BpfFilter)
			if !ok || bpfFilter.Name != owned.name ||
				filter.Attrs().Handle != netlink.MakeHandle(0, owned.handle) ||
				filter.Attrs().LinkIndex != link.Attrs().Index {
				continue
			}
			if err = netlink.FilterDel(filter); err != nil && !isMissingSharedLink(err) &&
				!errors.Is(err, unix.ESRCH) {
				return err
			}
		}
	}
	return nil
}

func removeEmptySharedClsact(link netlink.Link) error {
	for _, parent := range []uint32{netlink.HANDLE_MIN_INGRESS, netlink.HANDLE_MIN_EGRESS} {
		filters, err := netlink.FilterList(link, parent)
		if err != nil {
			return err
		}
		if len(filters) != 0 {
			return nil
		}
	}
	qdiscs, err := netlink.QdiscList(link)
	if err != nil {
		return err
	}
	for _, qdisc := range qdiscs {
		if qdisc.Type() != "clsact" || qdisc.Attrs().LinkIndex != link.Attrs().Index {
			continue
		}
		if err = netlink.QdiscDel(qdisc); err != nil && !isMissingSharedLink(err) && !errors.Is(err, unix.ESRCH) {
			return err
		}
		return nil
	}
	return nil
}

func isMissingSharedLink(err error) bool {
	if errors.Is(err, unix.ENODEV) || errors.Is(err, unix.ENOENT) {
		return true
	}
	var notFound netlink.LinkNotFoundError
	return errors.As(err, &notFound)
}
