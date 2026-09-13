//go:build linux && with_akihalink_observability

package akihalinkobs

import "testing"

func TestSharedFilterOwnershipIdentity(t *testing.T) {
	legacy := sharedNetworkJournal{}
	fixed := sharedNetworkJournal{IngressName: "sb_share_in", EgressName: "sb_share_out", IngressHandle: 0x5342, EgressHandle: 0x5343}
	temporary := sharedNetworkJournal{IngressName: "sbia", EgressName: "sboa", IngressHandle: 0x534c, EgressHandle: 0x534d}
	for _, record := range []sharedNetworkJournal{legacy, fixed, temporary} {
		if !validSharedFilterIdentity(record) {
			t.Fatalf("rejected an owned filter identity: %+v", record)
		}
	}
	for _, record := range []sharedNetworkJournal{
		{IngressName: "a", EgressName: "a", IngressHandle: 0x534c, EgressHandle: 0x534d},
		{IngressName: "sbia", EgressName: "sbob", IngressHandle: 0x534c, EgressHandle: 0x534d},
		{IngressName: "sbia", EgressName: "sboa", IngressHandle: 0x5342, EgressHandle: 0x5343},
		{IngressName: "sbi1000", EgressName: "sbo1000", IngressHandle: 0x6342, EgressHandle: 0x6343},
		{IngressName: "sbi0", EgressName: "sbo0", IngressHandle: 0x5342, EgressHandle: 0x5343},
	} {
		if validSharedFilterIdentity(record) {
			t.Fatalf("accepted an unrelated filter identity: %+v", record)
		}
	}
}
