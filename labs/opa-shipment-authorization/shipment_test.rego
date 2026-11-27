package shipment.authorization

import rego.v1

test_clerk_can_read_shipment_in_own_warehouse if {
	allow with input as {
		"action": "shipment.read",
		"principal": {"role": "warehouse_clerk", "warehouse": "WH-NORTH"},
		"shipment": {"warehouse": "WH-NORTH"},
	}
}

test_clerk_cannot_read_shipment_in_another_warehouse if {
	not allow with input as {
		"action": "shipment.read",
		"principal": {"role": "warehouse_clerk", "warehouse": "WH-NORTH"},
		"shipment": {"warehouse": "WH-SOUTH"},
	}
}

test_visitor_has_no_read_permission if {
	not allow with input as {
		"action": "shipment.read",
		"principal": {"role": "visitor", "warehouse": "WH-NORTH"},
		"shipment": {"warehouse": "WH-NORTH"},
	}
}

test_unknown_action_is_denied if {
	not allow with input as {
		"action": "shipment.write",
		"principal": {"role": "warehouse_clerk", "warehouse": "WH-NORTH"},
		"shipment": {"warehouse": "WH-NORTH"},
	}
}

test_empty_warehouse_is_denied if {
	not allow with input as {
		"action": "shipment.read",
		"principal": {"role": "warehouse_clerk", "warehouse": ""},
		"shipment": {"warehouse": ""},
	}
}
