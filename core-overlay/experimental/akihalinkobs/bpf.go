//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/cilium/ebpf"
	"github.com/cilium/ebpf/link"
	"github.com/cilium/ebpf/ringbuf"
	rtnetlink "github.com/sagernet/netlink"
	"golang.org/x/sys/unix"
)

type bpfObjects struct {
	Events           *ebpf.Map     `ebpf:"events"`
	Flows            *ebpf.Map     `ebpf:"flows"`
	Sequence         *ebpf.Map     `ebpf:"sequence"`
	Dropped          *ebpf.Map     `ebpf:"dropped"`
	Owner            *ebpf.Map     `ebpf:"owner"`
	TargetTgid       *ebpf.Map     `ebpf:"target_tgid"`
	AkihalinkSockops *ebpf.Program `ebpf:"akihalink_sockops"`
}

func (objects *bpfObjects) Close() {
	if objects.Events != nil {
		objects.Events.Close()
	}
	if objects.Flows != nil {
		objects.Flows.Close()
	}
	if objects.Sequence != nil {
		objects.Sequence.Close()
	}
	if objects.Dropped != nil {
		objects.Dropped.Close()
	}
	if objects.Owner != nil {
		objects.Owner.Close()
	}
	if objects.TargetTgid != nil {
		objects.TargetTgid.Close()
	}
	if objects.AkihalinkSockops != nil {
		objects.AkihalinkSockops.Close()
	}
}

type bpfRuntime struct {
	objects    bpfObjects
	link       link.Link
	reader     *ringbuf.Reader
	loaderMode string
	attachMode string
	generation uint64
	pinRoot    string
}

const ownerMagic uint64 = 0x414b4948414c494e

type ownerState struct {
	Magic           uint64
	ABIVersion      uint64
	Generation      uint64
	ProgramIdentity uint64
}

type resolverRuntimeStats struct {
	Discovered    uint64
	MonitorMode   string
	PlainDNS      uint64
	DNSOverTLS    uint64
	DNSOverHTTPS  uint64
	ProbeBypasses uint64
}

func readResolverRuntimeStats(pinRoot string) resolverRuntimeStats {
	var result resolverRuntimeStats
	mapRoot := filepath.Join(pinRoot, "redirect", "maps")
	resolverMap, err := ebpf.LoadPinnedMap(filepath.Join(mapRoot, "system_resolver_tgid_map"), nil)
	if err == nil {
		result.MonitorMode = "tgid_map"
		iterator := resolverMap.Iterate()
		var key uint32
		var value uint8
		for iterator.Next(&key, &value) {
			if key != 0 && value != 0 {
				result.Discovered++
			}
		}
		resolverMap.Close()
	}
	// The generic Android backend keeps this map private to the core process.
	// Keep diagnostics useful by reporting the same verified root netd discovery
	// used by the backend when no pinned map is available.
	if result.Discovered == 0 {
		result.Discovered = discoverRootNetdTGIDs("/proc")
		if result.Discovered > 0 {
			result.MonitorMode = "tgid_poll"
		}
	}
	counterMap, err := ebpf.LoadPinnedMap(filepath.Join(mapRoot, "resolver_counter_map"), nil)
	if err != nil {
		return result
	}
	defer counterMap.Close()
	values := []*uint64{
		&result.PlainDNS, &result.DNSOverTLS, &result.DNSOverHTTPS, &result.ProbeBypasses,
	}
	for index, target := range values {
		key := uint32(index)
		_ = counterMap.Lookup(&key, target)
	}
	return result
}

func discoverRootNetdTGIDs(procRoot string) uint64 {
	entries, err := os.ReadDir(procRoot)
	if err != nil {
		return 0
	}
	var discovered uint64
	for _, entry := range entries {
		_, err := strconv.ParseUint(entry.Name(), 10, 32)
		if err != nil || !entry.IsDir() {
			continue
		}
		processRoot := filepath.Join(procRoot, entry.Name())
		if isRootNetdProcess(processRoot) {
			discovered++
		}
	}
	return discovered
}

func isRootNetdProcess(processRoot string) bool {
	comm, err := os.ReadFile(filepath.Join(processRoot, "comm"))
	if err != nil {
		return false
	}
	threadName := strings.TrimSpace(string(comm))
	if !isRootNetdThreadName(threadName, filepath.Base(processRoot)) {
		return false
	}
	status, err := os.ReadFile(filepath.Join(processRoot, "status"))
	if err != nil || !hasRootUID(status) {
		return false
	}
	commandLine, err := os.ReadFile(filepath.Join(processRoot, "cmdline"))
	if err != nil {
		return false
	}
	command := strings.SplitN(string(commandLine), "\x00", 2)[0]
	executable, executableErr := os.Readlink(filepath.Join(processRoot, "exe"))
	return isRootNetdExecutable(command, executable, executableErr)
}

func isRootNetdExecutable(command, executable string, executableErr error) bool {
	if filepath.Base(command) != "netd" {
		return false
	}
	if executableErr == nil {
		return filepath.Base(strings.TrimSuffix(executable, " (deleted)")) == "netd"
	}
	return isPermissionError(executableErr) && isTrustedRootNetdCommand(command)
}

func isPermissionError(err error) bool {
	return errors.Is(err, os.ErrPermission) || errors.Is(err, unix.EPERM) || errors.Is(err, unix.EACCES)
}

func isTrustedRootNetdCommand(command string) bool {
	command = filepath.Clean(command)
	switch command {
	case "/system/bin/netd", "/system_ext/bin/netd", "/product/bin/netd", "/vendor/bin/netd", "/odm/bin/netd":
		return true
	default:
		return strings.HasPrefix(command, "/apex/") && strings.HasSuffix(command, "/bin/netd")
	}
}

func isRootNetdThreadName(threadName string, processName string) bool {
	if threadName == "netd" || threadName == "Binder:netd" {
		return true
	}
	threadIndex, found := strings.CutPrefix(threadName, "binder:"+processName+"_")
	if !found || threadIndex == "" {
		return false
	}
	_, err := strconv.ParseUint(threadIndex, 10, 32)
	return err == nil
}

func hasRootUID(status []byte) bool {
	for _, line := range strings.Split(string(status), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[0] == "Uid:" {
			return fields[1] == "0"
		}
	}
	return false
}

func loadBPF(pinRoot string) (*bpfRuntime, error) {
	spec, err := LoadAkihalink()
	if err != nil {
		return nil, fmt.Errorf("parse CO-RE object: %w", err)
	}
	var objects bpfObjects
	if err = spec.LoadAndAssign(&objects, nil); err != nil {
		return nil, fmt.Errorf("load CO-RE object: %w", err)
	}
	runtime := &bpfRuntime{
		objects:    objects,
		loaderMode: "core",
		attachMode: "none",
		generation: 1,
		pinRoot:    pinRoot,
	}
	if err = os.MkdirAll(pinRoot, 0o700); err != nil {
		runtime.Close(false)
		return nil, fmt.Errorf("create bpffs pin root: %w", err)
	}

	linkPath := filepath.Join(pinRoot, "sockops_link")
	programPath := filepath.Join(pinRoot, "sockops_program")
	ownerPath := filepath.Join(pinRoot, "owner")
	ownerValid := false
	previousGeneration := uint64(0)
	oldOwner, ownerErr := ebpf.LoadPinnedMap(ownerPath, nil)
	if ownerErr == nil {
		var key uint32
		var owner ownerState
		if err = oldOwner.Lookup(&key, &owner); err != nil ||
			owner.Magic != ownerMagic || owner.ABIVersion != eventABIVersion {
			oldOwner.Close()
			runtime.Close(false)
			return nil, errors.New("refusing unowned or incompatible AkihaLink BPF pins")
		}
		runtime.generation = owner.Generation + 1
		previousGeneration = owner.Generation
		ownerValid = true
		oldOwner.Close()
	}
	if !ownerValid {
		for _, name := range []string{
			"events", "flows", "sequence", "dropped", "sockops_program",
			"sockops_link", "target_tgid",
		} {
			if _, statErr := os.Lstat(filepath.Join(pinRoot, name)); statErr == nil {
				runtime.Close(false)
				return nil, errors.New("refusing telemetry pins without valid AkihaLink owner metadata")
			}
		}
	}
	oldProgram, oldProgramErr := ebpf.LoadPinnedProgram(programPath, nil)
	pinnedLink, pinnedLinkErr := link.LoadPinnedLink(linkPath, nil)
	if pinnedLinkErr == nil && !ownerValid {
		pinnedLink.Close()
		if oldProgram != nil {
			oldProgram.Close()
		}
		runtime.Close(false)
		return nil, errors.New("refusing telemetry link without valid AkihaLink owner metadata")
	}
	if pinnedLinkErr != nil && ownerValid {
		if oldProgram != nil {
			oldProgram.Close()
			oldProgram = nil
		}
		for _, name := range []string{
			"events", "flows", "sequence", "dropped", "sockops_program",
			"owner", "target_tgid",
		} {
			_ = os.Remove(filepath.Join(pinRoot, name))
		}
	}
	if pinnedLinkErr == nil {
		raw, ok := pinnedLink.(*link.RawLink)
		if !ok {
			pinnedLink.Close()
			runtime.Close(false)
			return nil, errors.New("pinned sockops link has unexpected type")
		}
		updateOptions := link.RawLinkUpdateOptions{New: objects.AkihalinkSockops}
		if oldProgramErr == nil {
			updateOptions.Old = oldProgram
		}
		if err = raw.UpdateArgs(updateOptions); err != nil {
			runtime.objects.Close()
			runtime.objects = bpfObjects{}
			runtime.link = pinnedLink
			runtime.loaderMode = "core_rollback"
			runtime.attachMode = "bpf_link_update_rollback"
			runtime.generation = previousGeneration
			if reopenErr := runtime.reopenPinnedObjects(oldProgram); reopenErr != nil {
				if oldProgram != nil {
					oldProgram.Close()
				}
				runtime.Close(false)
				return nil, fmt.Errorf(
					"atomic BPF_LINK_UPDATE failed (%v), reopen rollback failed: %w",
					err,
					reopenErr,
				)
			}
			runtime.reader, err = ringbuf.NewReader(runtime.objects.Events)
			if err != nil {
				runtime.Close(false)
				return nil, fmt.Errorf("open rolled-back ring buffer: %w", err)
			}
			return runtime, nil
		}
		runtime.link = pinnedLink
		runtime.attachMode = "bpf_link_update"
	} else {
		attached, attachErr := link.AttachCgroup(link.CgroupOptions{
			Path:    "/sys/fs/cgroup",
			Attach:  ebpf.AttachCGroupSockOps,
			Program: objects.AkihalinkSockops,
		})
		if attachErr != nil {
			if oldProgram != nil {
				oldProgram.Close()
			}
			runtime.Close(false)
			return nil, fmt.Errorf("attach sockops: %w", attachErr)
		}
		if err = attached.Pin(linkPath); err != nil {
			attached.Close()
			if oldProgram != nil {
				oldProgram.Close()
			}
			runtime.Close(false)
			return nil, fmt.Errorf("pin sockops link: %w", err)
		}
		runtime.link = attached
		runtime.attachMode = "bpf_link"
	}
	if oldProgram != nil {
		oldProgram.Close()
	}

	_ = os.Remove(programPath)
	if err = objects.AkihalinkSockops.Pin(programPath); err != nil {
		runtime.Close(false)
		return nil, fmt.Errorf("pin sockops program: %w", err)
	}
	var key uint32
	owner := ownerState{
		Magic: ownerMagic, ABIVersion: eventABIVersion,
		Generation: runtime.generation, ProgramIdentity: 0x534f434b4f505331,
	}
	if err = objects.Owner.Update(&key, &owner, ebpf.UpdateAny); err != nil {
		runtime.Close(false)
		return nil, fmt.Errorf("write owner metadata: %w", err)
	}
	for name, object := range map[string]*ebpf.Map{
		"events": objects.Events, "flows": objects.Flows,
		"sequence": objects.Sequence, "dropped": objects.Dropped, "owner": objects.Owner,
		"target_tgid": objects.TargetTgid,
	} {
		path := filepath.Join(pinRoot, name)
		_ = os.Remove(path)
		if pinErr := object.Pin(path); pinErr != nil {
			runtime.Close(false)
			return nil, fmt.Errorf("pin %s: %w", name, pinErr)
		}
	}
	runtime.reader, err = ringbuf.NewReader(objects.Events)
	if err != nil {
		runtime.Close(false)
		return nil, fmt.Errorf("open ring buffer: %w", err)
	}
	return runtime, nil
}

func (runtime *bpfRuntime) reopenPinnedObjects(oldProgram *ebpf.Program) error {
	loadMap := func(name string) (*ebpf.Map, error) {
		return ebpf.LoadPinnedMap(filepath.Join(runtime.pinRoot, name), nil)
	}
	var err error
	if runtime.objects.Events, err = loadMap("events"); err != nil {
		return err
	}
	if runtime.objects.Flows, err = loadMap("flows"); err != nil {
		return err
	}
	if runtime.objects.Sequence, err = loadMap("sequence"); err != nil {
		return err
	}
	if runtime.objects.Dropped, err = loadMap("dropped"); err != nil {
		return err
	}
	if runtime.objects.Owner, err = loadMap("owner"); err != nil {
		return err
	}
	if runtime.objects.TargetTgid, err = loadMap("target_tgid"); err != nil {
		return err
	}
	if oldProgram != nil {
		runtime.objects.AkihalinkSockops = oldProgram
	} else if runtime.objects.AkihalinkSockops, err =
		ebpf.LoadPinnedProgram(filepath.Join(runtime.pinRoot, "sockops_program"), nil); err != nil {
		return err
	}
	return nil
}

func (runtime *bpfRuntime) dropped() uint64 {
	if runtime == nil || runtime.objects.Dropped == nil {
		return 0
	}
	var key uint32
	var value uint64
	if runtime.objects.Dropped.Lookup(&key, &value) != nil {
		return 0
	}
	return value
}

func (runtime *bpfRuntime) Close(cleanup bool) {
	if runtime == nil {
		return
	}
	if runtime.reader != nil {
		runtime.reader.Close()
	}
	if cleanup && runtime.link != nil {
		_ = runtime.link.Unpin()
	}
	if runtime.link != nil {
		runtime.link.Close()
	}
	if cleanup && runtime.pinRoot != "" {
		for _, name := range []string{
			"events", "flows", "sequence", "dropped", "sockops_program",
			"owner",
			"target_tgid",
		} {
			_ = os.Remove(filepath.Join(runtime.pinRoot, name))
		}
		_ = os.Remove(filepath.Join(runtime.pinRoot, "generation"))
		_ = os.Remove(runtime.pinRoot)
	}
	runtime.objects.Close()
}

func (runtime *bpfRuntime) setCorePID(pid int) error {
	if runtime == nil || runtime.objects.TargetTgid == nil || pid <= 0 {
		return nil
	}
	var key uint32
	value := uint32(pid)
	return runtime.objects.TargetTgid.Update(&key, &value, ebpf.UpdateAny)
}

func probeBPF() (map[string]any, error) {
	result := map[string]any{
		"btfRootReadable": readable("/sys/kernel/btf/vmlinux"),
		"bpffs":           isBPFFS("/sys/fs/bpf"),
		"coreRelocation":  false,
		"bpfLink":         false,
		"linkUpdate":      false,
		"ringBuffer":      false,
		"sockOps":         false,
	}
	spec, err := LoadAkihalink()
	if err != nil {
		return result, err
	}
	var objects bpfObjects
	if err = spec.LoadAndAssign(&objects, nil); err != nil {
		return result, err
	}
	defer objects.Close()
	result["coreRelocation"] = true
	reader, readerErr := ringbuf.NewReader(objects.Events)
	if readerErr == nil {
		result["ringBuffer"] = true
		reader.Close()
	}
	result["sockOps"] = objects.AkihalinkSockops != nil
	attached, attachErr := link.AttachCgroup(link.CgroupOptions{
		Path: "/sys/fs/cgroup", Attach: ebpf.AttachCGroupSockOps,
		Program: objects.AkihalinkSockops,
	})
	if attachErr == nil {
		probePath := filepath.Join(
			"/sys/fs/bpf",
			fmt.Sprintf("akihalink-link-probe-%d", os.Getpid()),
		)
		_ = os.Remove(probePath)
		if pinErr := attached.Pin(probePath); pinErr == nil {
			result["bpfLink"] = true
			result["linkUpdate"] = attached.Update(objects.AkihalinkSockops) == nil
			_ = attached.Unpin()
			_ = os.Remove(probePath)
		}
		attached.Close()
	}
	return result, nil
}

func Probe() map[string]any {
	result, err := probeBPF()
	if err != nil {
		result["error"] = safeReason(err)
	}
	_, tcpInfoErr := rtnetlink.SocketDiagTCPInfo(unix.AF_INET)
	result["tcpInfo"] = tcpInfoErr == nil
	_, rtnetlinkErr := rtnetlink.LinkList()
	result["rtnetlink"] = rtnetlinkErr == nil
	result["tcpMtuProbing"] = readTCPMTUProbing()
	result["perSocketPmtu"] = false
	result["perSocketMss"] = false
	result["quicDplpmtud"] = true
	pidfdOpen, pidfdSendSignal, pidfdPoll := probePidfd()
	result["pidfdOpen"] = pidfdOpen
	result["pidfdSendSignal"] = pidfdSendSignal
	result["pidfdPoll"] = pidfdPoll
	return result
}

func isBPFFS(path string) bool {
	var fileSystem unix.Statfs_t
	return unix.Statfs(path, &fileSystem) == nil && fileSystem.Type == unix.BPF_FS_MAGIC
}
