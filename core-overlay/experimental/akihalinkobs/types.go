//go:build linux && with_akihalink_observability

package akihalinkobs

import "time"

const (
	eventABIVersion = 1
	windowDuration  = 15 * time.Minute
)

type bpfEvent struct {
	ABIVersion   uint16
	EventType    uint16
	State        uint32
	Sequence     uint64
	MonotonicNS  uint64
	SocketCookie uint64
	HandshakeUS  uint32
	SRTTUS       uint32
	SendCWND     uint32
	TotalRetrans uint32
	SegmentsOut  uint32
	Reserved     uint32
	DeliveryRate uint64
}

type FlowSnapshot struct {
	FlowID            string  `json:"flowId"`
	Protocol          string  `json:"protocol"`
	StartedAt         int64   `json:"startedAt"`
	DurationMs        int64   `json:"durationMs"`
	State             string  `json:"state"`
	RTTMs             float64 `json:"rttMs"`
	HandshakeMs       float64 `json:"handshakeMs"`
	SendCWND          uint32  `json:"sendCwnd"`
	DeliveryRateBps   uint64  `json:"deliveryRateBps"`
	TotalRetrans      uint32  `json:"totalRetrans"`
	EstimatedLossRate float64 `json:"estimatedLossRate"`
	Failure           string  `json:"failure,omitempty"`
}

type PathMtuEntry struct {
	Fingerprint     string `json:"fingerprint"`
	AddressFamily   string `json:"addressFamily"`
	RouteGeneration uint64 `json:"routeGeneration"`
	PMTU            int    `json:"pmtu,omitempty"`
	MSS             int    `json:"mss,omitempty"`
	BlackholeCount  uint64 `json:"blackholeCount"`
	LastUpdatedAt   int64  `json:"lastUpdatedAt"`
	ReprobeAfter    int64  `json:"reprobeAfter,omitempty"`
}

type ProcessSupervisorStatus struct {
	Mode            string `json:"mode"`
	PidfdOpen       bool   `json:"pidfdOpen"`
	PidfdSendSignal bool   `json:"pidfdSendSignal"`
	PidfdPoll       bool   `json:"pidfdPoll"`
	ManagedPID      int    `json:"managedPid,omitempty"`
}

// RuntimeStatus is the bounded, aggregate-only control-plane view consumed by
// module status and diagnostics. It deliberately excludes process identifiers,
// flows, destinations, node fingerprints, and historical arrays.
type RuntimeStatus struct {
	Running                     bool   `json:"running"`
	LoaderMode                  string `json:"loaderMode"`
	AttachMode                  string `json:"attachMode"`
	EventMode                   string `json:"eventMode"`
	PinGeneration               uint64 `json:"pinGeneration"`
	ObservabilityDegradedReason string `json:"observabilityDegradedReason,omitempty"`
	RouteGeneration             uint64 `json:"routeGeneration"`
	TCPMTUProbing               int    `json:"tcpMtuProbing"`
	TCPMode                     string `json:"tcpMode"`
	PerSocketPMTU               bool   `json:"perSocketPmtu"`
	PerSocketMSS                bool   `json:"perSocketMss"`
	QUICDPLPMTUD                bool   `json:"quicDplpmtud"`
	MTUDegradedReason           string `json:"mtuDegradedReason,omitempty"`
	SupervisorMode              string `json:"supervisorMode"`
	LogMonitorMode              string `json:"logMonitorMode"`
	TCPActiveSamples            uint64 `json:"tcpActiveSamples"`
	TCPIdleSamples              uint64 `json:"tcpIdleSamples"`
	TCPSamplingMode             string `json:"tcpSamplingMode"`
	TCPSamplingLeaseActive      bool   `json:"tcpSamplingLeaseActive"`
	TCPSamplingLeaseExpiresAt   int64  `json:"tcpSamplingLeaseExpiresAt,omitempty"`
	TCPSampleGeneration         uint64 `json:"tcpSampleGeneration"`
	SystemResolverDiscovered    uint64 `json:"systemResolverDiscovered"`
	DNSPlainCaptureCount        uint64 `json:"dnsPlainCaptureCount"`
	DNSOverTLSCaptureCount      uint64 `json:"dnsOverTlsCaptureCount"`
	DNSOverHTTPSCaptureCount    uint64 `json:"dnsOverHttpsCaptureCount"`
	ResolverProbeBypassCount    uint64 `json:"resolverProbeBypassCount"`
	ResolverMonitorMode         string `json:"resolverMonitorMode"`
	ResolverRediscoveries       uint64 `json:"resolverRediscoveries"`
}

type PathStatus struct {
	RouteGeneration uint64                  `json:"routeGeneration"`
	TCPMTUProbing   int                     `json:"tcpMtuProbing"`
	TCPMode         string                  `json:"tcpMode"`
	PerSocketPMTU   bool                    `json:"perSocketPmtu"`
	PerSocketMSS    bool                    `json:"perSocketMss"`
	QUICDPLPMTUD    bool                    `json:"quicDplpmtud"`
	DegradedReason  string                  `json:"degradedReason,omitempty"`
	Paths           []PathMtuEntry          `json:"paths"`
	Supervisor      ProcessSupervisorStatus `json:"supervisor"`
}

type TelemetrySummary struct {
	Running           bool              `json:"running"`
	DaemonPID         int               `json:"daemonPid,omitempty"`
	CorePID           int               `json:"corePid,omitempty"`
	WindowSeconds     int               `json:"windowSeconds"`
	LoaderMode        string            `json:"loaderMode"`
	AttachMode        string            `json:"attachMode"`
	EventMode         string            `json:"eventMode"`
	PinGeneration     uint64            `json:"pinGeneration"`
	DegradedReason    string            `json:"degradedReason,omitempty"`
	ActiveFlows       int               `json:"activeFlows"`
	TotalFlows        int               `json:"totalFlows"`
	RTTMedianMs       float64           `json:"rttMedianMs"`
	RTTP95Ms          float64           `json:"rttP95Ms"`
	HandshakeMedianMs float64           `json:"handshakeMedianMs"`
	HandshakeP95Ms    float64           `json:"handshakeP95Ms"`
	DeliveryRateBps   uint64            `json:"deliveryRateBps"`
	EstimatedLossRate float64           `json:"estimatedLossRate"`
	TotalRetrans      uint64            `json:"totalRetrans"`
	Failures          map[string]uint64 `json:"failures"`
	RingBufferDropped uint64            `json:"ringBufferDropped"`
	RecentFlows       []FlowSnapshot    `json:"recentFlows"`
}

type NetworkEvent struct {
	Sequence        uint64 `json:"sequence"`
	RouteGeneration uint64 `json:"routeGeneration"`
	Timestamp       int64  `json:"timestamp"`
	Type            string `json:"type"`
	Interface       string `json:"interface,omitempty"`
	MTU             int    `json:"mtu,omitempty"`
	IPv4            bool   `json:"ipv4"`
	IPv6            bool   `json:"ipv6"`
	DefaultRoute    bool   `json:"defaultRoute"`
	Detail          string `json:"detail,omitempty"`
}

type ObservabilitySnapshot struct {
	Telemetry     *TelemetrySummary `json:"telemetry,omitempty"`
	PathStatus    PathStatus        `json:"pathStatus"`
	NetworkEvents []NetworkEvent    `json:"networkEvents"`
}

type commandRequest struct {
	Command     string `json:"command"`
	Limit       int    `json:"limit,omitempty"`
	CorePID     int    `json:"corePid,omitempty"`
	Target      string `json:"target,omitempty"`
	Fingerprint string `json:"fingerprint,omitempty"`
}

type commandResponse struct {
	OK            bool                     `json:"ok"`
	Error         string                   `json:"error,omitempty"`
	Telemetry     *TelemetrySummary        `json:"telemetry,omitempty"`
	PathStatus    *PathStatus              `json:"pathStatus,omitempty"`
	Supervisor    *ProcessSupervisorStatus `json:"supervisor,omitempty"`
	NetworkEvents []NetworkEvent           `json:"networkEvents,omitempty"`
	Capabilities  map[string]any           `json:"capabilities,omitempty"`
	RuntimeStatus *RuntimeStatus           `json:"runtimeStatus,omitempty"`
	Snapshot      *ObservabilitySnapshot   `json:"snapshot,omitempty"`
}
