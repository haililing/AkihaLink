//go:build linux && with_akihalink_observability

package akihalinkobs

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"time"
)

type uidPolicyConfig struct {
	Inbounds []json.RawMessage `json:"inbounds"`
}

type uidPolicyInbound struct {
	Type  string `json:"type"`
	Local struct {
		ExcludeUID      []uint32 `json:"exclude_uid"`
		ExcludeUIDRange []string `json:"exclude_uid_range"`
	} `json:"local"`
}

type UIDPolicyResetTarget struct {
	UID         uint32 `json:"uid"`
	UserID      uint32 `json:"userId"`
	PackageName string `json:"packageName"`
}

type UIDPolicyResetRequest struct {
	Packages   []UIDPolicyResetTarget `json:"packages"`
	VerifyUIDs []uint32               `json:"verifyUids"`
}

type UIDPolicyStatus struct {
	Generation            uint64 `json:"generation"`
	ConfiguredFingerprint string `json:"configuredFingerprint"`
	LiveFingerprint       string `json:"liveFingerprint"`
	EffectiveUIDs         int    `json:"effectiveUids"`
	TCPBypassHits         uint64 `json:"tcpBypassHits"`
	UDPBypassHits         uint64 `json:"udpBypassHits"`
	InSync                bool   `json:"inSync"`
	Known                 bool   `json:"known"`
}

type UIDPolicyConnectionReset struct {
	RequestedPackages int      `json:"requestedPackages"`
	StoppedPackages   int      `json:"stoppedPackages"`
	VerifiedUIDs      int      `json:"verifiedUids"`
	RemainingUIDs     []uint32 `json:"remainingUids"`
}

type UIDPolicyApplyResponse struct {
	OK              bool                     `json:"ok"`
	Error           string                   `json:"error,omitempty"`
	PolicyStatus    UIDPolicyStatus          `json:"policyStatus"`
	ConnectionReset UIDPolicyConnectionReset `json:"connectionReset"`
}

type uidPolicyUpdateRequest struct {
	Action          string                 `json:"action,omitempty"`
	ExcludeUID      []uint32               `json:"exclude_uid,omitempty"`
	ExcludeUIDRange []string               `json:"exclude_uid_range,omitempty"`
	Packages        []UIDPolicyResetTarget `json:"packages,omitempty"`
	VerifyUIDs      []uint32               `json:"verifyUids,omitempty"`
}

// UpdateExcludedUIDPolicy retains the lightweight live-map update used by
// maintenance commands. User-facing changes should use ApplyExcludedUIDPolicy
// so stale application sockets are reset before state is committed.
func UpdateExcludedUIDPolicy(socketPath, configPath string) error {
	_, err := applyExcludedUIDPolicy(socketPath, configPath, UIDPolicyResetRequest{})
	return err
}

func ApplyExcludedUIDPolicy(
	socketPath string,
	configPath string,
	resetPath string,
) (UIDPolicyApplyResponse, error) {
	payload, err := os.ReadFile(resetPath)
	if err != nil {
		return UIDPolicyApplyResponse{}, err
	}
	var reset UIDPolicyResetRequest
	if err = json.Unmarshal(payload, &reset); err != nil {
		return UIDPolicyApplyResponse{}, fmt.Errorf("decode UID policy reset targets: %w", err)
	}
	return applyExcludedUIDPolicy(socketPath, configPath, reset)
}

func ReadUIDPolicyStatus(socketPath string) (UIDPolicyStatus, error) {
	response, err := sendUIDPolicyRequest(socketPath, uidPolicyUpdateRequest{Action: "status"})
	if err != nil {
		return UIDPolicyStatus{}, err
	}
	if !response.OK {
		return response.PolicyStatus, responseError(response)
	}
	return response.PolicyStatus, nil
}

func applyExcludedUIDPolicy(
	socketPath string,
	configPath string,
	reset UIDPolicyResetRequest,
) (UIDPolicyApplyResponse, error) {
	inbound, err := readUIDPolicyInbound(configPath)
	if err != nil {
		return UIDPolicyApplyResponse{}, err
	}
	response, err := sendUIDPolicyRequest(socketPath, uidPolicyUpdateRequest{
		Action:          "apply",
		ExcludeUID:      inbound.Local.ExcludeUID,
		ExcludeUIDRange: inbound.Local.ExcludeUIDRange,
		Packages:        reset.Packages,
		VerifyUIDs:      reset.VerifyUIDs,
	})
	if err != nil {
		return UIDPolicyApplyResponse{}, err
	}
	if !response.OK {
		return response, responseError(response)
	}
	return response, nil
}

func readUIDPolicyInbound(configPath string) (*uidPolicyInbound, error) {
	payload, err := os.ReadFile(configPath)
	if err != nil {
		return nil, err
	}
	var config uidPolicyConfig
	if err = json.Unmarshal(payload, &config); err != nil {
		return nil, fmt.Errorf("decode staged config: %w", err)
	}
	var inbound *uidPolicyInbound
	for _, rawInbound := range config.Inbounds {
		var candidate uidPolicyInbound
		if err = json.Unmarshal(rawInbound, &candidate); err != nil {
			return nil, fmt.Errorf("decode inbound: %w", err)
		}
		if candidate.Type != "ebpf" {
			continue
		}
		if inbound != nil {
			return nil, errors.New("multiple eBPF inbounds are not supported")
		}
		inbound = &candidate
	}
	if inbound == nil {
		return nil, errors.New("missing eBPF inbound")
	}
	return inbound, nil
}

func sendUIDPolicyRequest(socketPath string, request uidPolicyUpdateRequest) (UIDPolicyApplyResponse, error) {
	connection, err := net.DialTimeout("unix", socketPath, 2*time.Second)
	if err != nil {
		return UIDPolicyApplyResponse{}, err
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(12 * time.Second))
	if err = json.NewEncoder(connection).Encode(request); err != nil {
		return UIDPolicyApplyResponse{}, err
	}
	var response UIDPolicyApplyResponse
	if err = json.NewDecoder(io.LimitReader(connection, 1<<20)).Decode(&response); err != nil {
		return UIDPolicyApplyResponse{}, err
	}
	return response, nil
}

func responseError(response UIDPolicyApplyResponse) error {
	if response.Error == "" {
		return errors.New("eBPF UID policy update failed")
	}
	return errors.New(response.Error)
}
