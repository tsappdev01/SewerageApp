package com.meterreading.reader.data

import com.meterreading.reader.platform.*
/**
 * FR-006.12: before a reading is saved or sent, the reader checks the property's tenant. Mirrors the
 * server's check (NO_TENANT, TENANT_NOT_CONFIRMED, TENANT_CHANGED); the server decides at upload.
 */
object TenantRules {
    enum class Problem(val message: String) {
        NO_TENANT("This property has no tenant on record. Tell your supervisor."),
        NOT_CHECKED("Check the tenant first."),
        NOT_THIS_PROPERTY("This tenant is not on this property. Check the tenant again."),
    }

    fun problem(property: Property, tenantCode: String?): Problem? = when {
        property.tenants.isEmpty() -> Problem.NO_TENANT
        tenantCode.isNullOrBlank() -> Problem.NOT_CHECKED
        property.tenants.none { it.code.equals(tenantCode.trim(), ignoreCase = true) } -> Problem.NOT_THIS_PROPERTY
        else -> null
    }

    /** The only tenant, or null when the reader must choose (or there is none). Never chosen for them. */
    fun onlyTenant(property: Property): Tenant? = property.tenants.singleOrNull()
}
