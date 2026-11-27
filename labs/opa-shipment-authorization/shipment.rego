package shipment.authorization

import rego.v1

default allow := false

allow if {
	input.action == "shipment.read"
	input.principal.role == "warehouse_clerk"
	is_string(input.principal.warehouse)
	input.principal.warehouse != ""
	input.principal.warehouse == input.shipment.warehouse
}
