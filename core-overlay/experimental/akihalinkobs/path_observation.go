//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"os"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	pathReprobeInterval    = 10 * time.Minute
	pathValidationDuration = 30 * time.Second
	pathValidationBytes    = 1024 * 1024
)

var pmtuPlateaus = []int{1500, 1492, 1472, 1460, 1420, 1380, 1280, 1200, 1000, 576}

type pathAccumulator struct {
	Fingerprint     string
	AddressFamily   string
	RouteGeneration uint64
	PMTU            int
	MSS             int
	BlackholeCount  uint64
	UpdatedAt       time.Time
	ReprobeAfter    time.Time
	ReprobeMSS      int
}

func (state *daemon) ensurePathLocked(fingerprint, family string) *pathAccumulator {
	key := pathKey(fingerprint, family, state.routeGeneration)
	if path := state.paths[key]; path != nil {
		return path
	}
	path := &pathAccumulator{
		Fingerprint: fingerprint, AddressFamily: family,
		RouteGeneration: state.routeGeneration, UpdatedAt: time.Now(),
	}
	state.paths[key] = path
	return path
}

func (state *daemon) lowerPathLocked(path *pathAccumulator) {
	current := path.PMTU
	if current <= 0 {
		current = state.defaultRouteMTU
	}
	if current <= 0 {
		current = 1500
	}
	floor := 576
	if path.AddressFamily == "ipv6" {
		floor = 1280
	}
	next := floor
	for _, plateau := range pmtuPlateaus {
		if plateau < current && plateau >= floor {
			next = plateau
			break
		}
	}
	path.PMTU = next
	path.MSS = max(256, next-headerSize(path.AddressFamily))
	path.BlackholeCount++
	path.UpdatedAt = time.Now()
	path.ReprobeAfter = path.UpdatedAt.Add(pathReprobeInterval)
	path.ReprobeMSS = nextHigherMSS(next, path.AddressFamily)
}

func nextHigherMSS(pmtu int, family string) int {
	upper := pmtu
	for index := len(pmtuPlateaus) - 1; index >= 0; index-- {
		if pmtuPlateaus[index] > pmtu {
			upper = pmtuPlateaus[index]
			break
		}
	}
	return max(256, upper-headerSize(family))
}

func (state *daemon) pathStatus() PathStatus {
	state.mu.Lock()
	entries := make([]PathMtuEntry, 0, len(state.paths))
	for _, path := range state.paths {
		entries = append(entries, PathMtuEntry{
			Fingerprint: path.Fingerprint, AddressFamily: path.AddressFamily,
			RouteGeneration: path.RouteGeneration, PMTU: path.PMTU, MSS: path.MSS,
			BlackholeCount: path.BlackholeCount, LastUpdatedAt: path.UpdatedAt.UnixMilli(),
			ReprobeAfter: timeToMillis(path.ReprobeAfter),
		})
	}
	routeGeneration := state.routeGeneration
	tcpMTUProbing := state.tcpMTUProbing
	perSocketPMTU := state.perSocketPMTU
	perSocketMSS := state.perSocketMSS
	state.mu.Unlock()
	sort.Slice(entries, func(i, j int) bool {
		if entries[i].Fingerprint != entries[j].Fingerprint {
			return entries[i].Fingerprint < entries[j].Fingerprint
		}
		return entries[i].AddressFamily < entries[j].AddressFamily
	})
	degradedReason := ""
	if !perSocketPMTU || !perSocketMSS {
		degradedReason = "per_socket_mtu_unavailable"
	}
	return PathStatus{
		RouteGeneration: routeGeneration,
		TCPMTUProbing:   tcpMTUProbing,
		TCPMode:         tcpMTUMode(tcpMTUProbing),
		PerSocketPMTU:   perSocketPMTU, PerSocketMSS: perSocketMSS,
		QUICDPLPMTUD: true, DegradedReason: degradedReason,
		Paths:      entries,
		Supervisor: state.supervisorStatus(),
	}
}

func readTCPMTUProbing() int {
	payload, err := os.ReadFile("/proc/sys/net/ipv4/tcp_mtu_probing")
	if err != nil {
		return -1
	}
	value, err := strconv.Atoi(strings.TrimSpace(string(payload)))
	if err != nil {
		return -1
	}
	return value
}

func tcpMTUMode(value int) string {
	switch value {
	case 2:
		return "kernel_always"
	case 1:
		return "kernel_blackhole"
	case 0:
		return "kernel_disabled"
	default:
		return "unavailable"
	}
}

func headerSize(family string) int {
	if family == "ipv6" {
		return 60
	}
	return 40
}

func clampPMTU(value int, family string) int {
	floor := 576
	if family == "ipv6" {
		floor = 1280
	}
	return min(max(value, floor), 65535)
}

func pathKey(fingerprint, family string, generation uint64) string {
	return fingerprint + ":" + family + ":" + strconv.FormatUint(generation, 10)
}

func validFingerprint(value string) bool {
	if len(value) != 64 {
		return false
	}
	for _, character := range value {
		if character < '0' || character > '9' && character < 'a' || character > 'f' {
			return false
		}
	}
	return true
}

func timeToMillis(value time.Time) int64 {
	if value.IsZero() {
		return 0
	}
	return value.UnixMilli()
}
