package vn.servio.customer.data.model

import kotlinx.serialization.Serializable

/**
 * One entry of the address book: AddressDto of GET/POST/PUT /users/me/addresses (#12–#14).
 * Field names follow the API contract on Swagger. [label] is free text (max 50 characters); CS-12 offers
 * "Nhà", "Cơ quan", "Khác" (strings address_label_*) as quick choices.
 */
@Serializable
data class Address(
    val id: String,
    val label: String,
    val receiverName: String,
    val receiverPhone: String,
    val fullAddress: String,
    val latitude: Double,
    val longitude: Double,
    val note: String? = null,
    val isDefault: Boolean,
)

/** Body of POST /users/me/addresses (#13) and PUT /users/me/addresses/{id} (#14). */
@Serializable
data class SaveAddressRequest(
    val label: String,
    val receiverName: String,
    val receiverPhone: String,
    val fullAddress: String,
    val latitude: Double,
    val longitude: Double,
    val note: String? = null,
    val isDefault: Boolean,
)

fun Address.toSaveRequest() = SaveAddressRequest(
    label = label,
    receiverName = receiverName,
    receiverPhone = receiverPhone,
    fullAddress = fullAddress,
    latitude = latitude,
    longitude = longitude,
    note = note,
    isDefault = isDefault,
)
