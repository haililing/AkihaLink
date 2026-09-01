//go:build linux && with_akihalink_observability

package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/sagernet/sing-box/experimental/akihalinkobs"
	"github.com/spf13/cobra"
	"golang.org/x/sys/unix"
)

const (
	defaultAkihaSocket          = "/data/adb/akihalink/runtime/observability.sock"
	defaultAkihaUIDPolicySocket = "/data/adb/akihalink/runtime/uid-policy.sock"
	defaultAkihaPinRoot         = "/sys/fs/bpf/akihalink/generic/v1"
)

var (
	akihalinkSocket          string
	akihalinkUIDPolicySocket string
	akihalinkPinRoot         string
	akihalinkRuntimeConfig   string
	akihalinkCoreLog         string
	akihalinkCorePath        string
	akihalinkRuntimeDir      string
	akihalinkAuxTimeout      time.Duration
)

var commandAkihaDaemon = &cobra.Command{
	Use:    "akihalink-daemon",
	Short:  "AkihaLink internal observability daemon",
	Hidden: true,
}

var commandAkihaDaemonRun = &cobra.Command{
	Use:   "run",
	Short: "run the private daemon",
	RunE: func(_ *cobra.Command, _ []string) error {
		return akihalinkobs.RunDaemon(
			akihalinkSocket,
			akihalinkPinRoot,
			akihalinkRuntimeConfig,
			akihalinkCoreLog,
		)
	},
}

var commandAkihaDaemonSupervise = &cobra.Command{
	Use:   "supervise",
	Short: "run observability and supervise the private proxy core",
	Args:  cobra.NoArgs,
	RunE: func(_ *cobra.Command, _ []string) error {
		return akihalinkobs.RunSupervised(
			akihalinkSocket,
			akihalinkPinRoot,
			akihalinkRuntimeConfig,
			akihalinkCoreLog,
			akihalinkobs.SupervisorOptions{
				CorePath: akihalinkCorePath, ConfigPath: akihalinkRuntimeConfig,
				LogPath: akihalinkCoreLog, RuntimeDir: akihalinkRuntimeDir,
			},
		)
	},
}

var commandAkihaDaemonProbe = &cobra.Command{
	Use:   "probe",
	Short: "perform disposable observability capability probes",
	Args:  cobra.NoArgs,
	RunE: func(_ *cobra.Command, _ []string) error {
		output, err := json.Marshal(akihalinkobs.Probe())
		if err != nil {
			return err
		}
		fmt.Fprintln(os.Stdout, string(output))
		return nil
	},
}

var commandAkihaDaemonCleanupPins = &cobra.Command{
	Use:   "cleanup-pins",
	Short: "remove verified AkihaLink redirect pins",
	Args:  cobra.NoArgs,
	RunE: func(_ *cobra.Command, _ []string) error {
		return akihalinkobs.CleanupPersistentRedirect()
	},
}

var commandAkihaDaemonCleanupSharedNetwork = &cobra.Command{
	Use:   "cleanup-shared-network",
	Short: "remove verified AkihaLink shared-network TC state",
	Args:  cobra.NoArgs,
	RunE: func(_ *cobra.Command, _ []string) error {
		return akihalinkobs.CleanupSharedNetwork(akihalinkRuntimeDir)
	},
}

var commandAkihaDaemonUpdateUIDPolicy = &cobra.Command{
	Use:   "update-uid-policy <config>",
	Short: "update the running eBPF exclusion policy",
	Args:  cobra.ExactArgs(1),
	RunE: func(_ *cobra.Command, args []string) error {
		return akihalinkobs.UpdateExcludedUIDPolicy(akihalinkUIDPolicySocket, args[0])
	},
}

var commandAkihaDaemonApplyUIDPolicy = &cobra.Command{
	Use:   "apply-uid-policy <config> <reset-targets>",
	Short: "update the running eBPF exclusion policy and reset stale application sockets",
	Args:  cobra.ExactArgs(2),
	RunE: func(_ *cobra.Command, args []string) error {
		response, err := akihalinkobs.ApplyExcludedUIDPolicy(
			akihalinkUIDPolicySocket, args[0], args[1],
		)
		output, marshalErr := json.Marshal(response.ConnectionReset)
		if marshalErr != nil {
			return marshalErr
		}
		fmt.Fprintln(os.Stdout, string(output))
		return err
	},
}

var commandAkihaDaemonUIDPolicyStatus = &cobra.Command{
	Use:   "uid-policy-status",
	Short: "read the live eBPF UID exclusion policy state",
	Args:  cobra.NoArgs,
	RunE: func(_ *cobra.Command, _ []string) error {
		status, err := akihalinkobs.ReadUIDPolicyStatus(akihalinkUIDPolicySocket)
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(status)
	},
}

var commandAkihaDaemonMigrateConfig = &cobra.Command{
	Use:   "migrate-config <config>",
	Short: "atomically migrate a verified AkihaLink v14 eBPF configuration",
	Args:  cobra.ExactArgs(1),
	RunE: func(_ *cobra.Command, args []string) error {
		result, err := akihalinkobs.MigrateV14Config(args[0])
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(result)
	},
}

var commandAkihaDaemonCompareConfig = &cobra.Command{
	Use:   "compare-config-except-uid <current> <candidate>",
	Short: "compare normalized v15 configurations excluding local UID exclusions",
	Args:  cobra.ExactArgs(2),
	RunE: func(_ *cobra.Command, args []string) error {
		equal, err := akihalinkobs.ConfigsEqualExceptUIDExclusions(args[0], args[1])
		if err != nil {
			return err
		}
		if !equal {
			return fmt.Errorf("configuration changed outside local UID exclusions")
		}
		return nil
	},
}

var commandAkihaDaemonClient = &cobra.Command{
	Use:   "client <command> [value] [fingerprint]",
	Short: "send a private daemon command",
	Args:  cobra.RangeArgs(1, 3),
	RunE: func(_ *cobra.Command, args []string) error {
		limit := 0
		corePID := 0
		target := ""
		fingerprint := ""
		if len(args) == 2 {
			if args[0] == "target" {
				target = args[1]
			} else {
				value, err := strconv.Atoi(args[1])
				if err != nil {
					return err
				}
				if args[0] == "ready" {
					corePID = value
				} else {
					limit = value
				}
			}
		}
		if len(args) == 3 && args[0] == "target" {
			target = args[1]
			fingerprint = args[2]
		}
		response, err := akihalinkobs.SendCommandForCLI(
			akihalinkSocket, args[0], limit, corePID, target, fingerprint,
		)
		if err != nil {
			return err
		}
		output, err := json.Marshal(response)
		if err != nil {
			return err
		}
		fmt.Fprintln(os.Stdout, string(output))
		return nil
	},
}

var commandAkihaDaemonProcess = &cobra.Command{
	Use:   "process <status|signal> <pid> <expected> [start-time] [signal]",
	Short: "perform PID-reuse-safe process operations",
	Args:  cobra.RangeArgs(3, 5),
	RunE: func(_ *cobra.Command, args []string) error {
		pid, err := strconv.Atoi(args[1])
		if err != nil || pid <= 0 {
			return fmt.Errorf("invalid PID")
		}
		expectedStart := ""
		if len(args) >= 4 {
			expectedStart = args[3]
		}
		switch args[0] {
		case "status":
			if !akihalinkobs.ProcessStatus(pid, args[2], expectedStart) {
				return fmt.Errorf("process unavailable")
			}
			fmt.Fprintln(os.Stdout, "running")
			return nil
		case "signal":
			signal := 15
			if len(args) == 5 {
				signal, err = strconv.Atoi(args[4])
				if err != nil {
					return err
				}
			}
			return akihalinkobs.ProcessSignal(pid, unix.Signal(signal), args[2], expectedStart)
		default:
			return fmt.Errorf("unknown process command")
		}
	},
}

var commandAkihaDaemonAuxiliary = &cobra.Command{
	Use:   "auxiliary <name> <config> <log>",
	Short: "supervise one private auxiliary core",
	Args:  cobra.ExactArgs(3),
	RunE: func(_ *cobra.Command, args []string) error {
		return akihalinkobs.RunAuxiliary(
			akihalinkCorePath,
			args[1],
			args[2],
			akihalinkRuntimeDir,
			args[0],
			akihalinkAuxTimeout,
		)
	},
}

func init() {
	commandAkihaDaemon.PersistentFlags().StringVar(
		&akihalinkSocket, "socket", defaultAkihaSocket, "private Unix socket",
	)
	commandAkihaDaemon.PersistentFlags().StringVar(
		&akihalinkUIDPolicySocket, "uid-policy-socket", defaultAkihaUIDPolicySocket,
		"private eBPF UID policy socket",
	)
	commandAkihaDaemonAuxiliary.Flags().DurationVar(
		&akihalinkAuxTimeout,
		"timeout",
		30*time.Minute,
		"maximum auxiliary lifetime",
	)
	commandAkihaDaemon.PersistentFlags().StringVar(
		&akihalinkCoreLog,
		"core-log",
		"/data/adb/akihalink/log/core.log",
		"active AkihaLink core log",
	)
	commandAkihaDaemon.PersistentFlags().StringVar(
		&akihalinkPinRoot, "pin-root", defaultAkihaPinRoot, "owned bpffs root",
	)
	commandAkihaDaemon.PersistentFlags().StringVar(
		&akihalinkRuntimeConfig,
		"runtime-config",
		"/data/adb/akihalink/config/current.json",
		"active AkihaLink config",
	)
	commandAkihaDaemon.PersistentFlags().StringVar(
		&akihalinkCorePath,
		"core",
		"/data/adb/modules/akihalink/bin/sing-box",
		"private sing-box core",
	)
	commandAkihaDaemon.PersistentFlags().StringVar(
		&akihalinkRuntimeDir,
		"runtime",
		"/data/adb/akihalink/runtime",
		"private runtime directory",
	)
	commandAkihaDaemon.AddCommand(
		commandAkihaDaemonRun,
		commandAkihaDaemonSupervise,
		commandAkihaDaemonProbe,
		commandAkihaDaemonCleanupPins,
		commandAkihaDaemonCleanupSharedNetwork,
		commandAkihaDaemonUpdateUIDPolicy,
		commandAkihaDaemonApplyUIDPolicy,
		commandAkihaDaemonUIDPolicyStatus,
		commandAkihaDaemonMigrateConfig,
		commandAkihaDaemonCompareConfig,
		commandAkihaDaemonClient,
		commandAkihaDaemonProcess,
		commandAkihaDaemonAuxiliary,
	)
	mainCommand.AddCommand(commandAkihaDaemon)
}
