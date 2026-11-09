package vn.servio.customer.ui.request

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.servio.core.utils.UiState
import vn.servio.customer.data.model.Address
import vn.servio.customer.ui.request.CreateRequestViewModel.Companion.addressErrors
import vn.servio.customer.ui.request.CreateRequestViewModel.FieldError

class AddressErrorsTest {

    private val home = Address(
        id = "a1",
        label = "Nhà",
        receiverName = "Khach",
        receiverPhone = "0912345678",
        fullAddress = "12 Nguyen Van Linh",
        latitude = 16.06,
        longitude = 108.22,
        isDefault = true,
    )

    @Test
    fun loadedAddressHasNoError() {
        assertEquals(emptySet<FieldError>(), addressErrors(home, UiState.Content(home)))
    }

    // Empty address book: the card says "Chưa có địa chỉ".
    @Test
    fun emptyBookIsRequired() {
        assertEquals(setOf(FieldError.ADDRESS_REQUIRED), addressErrors(null, UiState.Content(null)))
    }

    @Test
    fun loadedCardWithoutDraftAddressIsRequired() {
        assertEquals(setOf(FieldError.ADDRESS_REQUIRED), addressErrors(null, UiState.Content(home)))
    }

    // The draft may still hold the previous address while the card loads another one.
    @Test
    fun loadingIsNotReady() {
        assertEquals(setOf(FieldError.ADDRESS_NOT_READY), addressErrors(home, UiState.Loading))
        assertEquals(setOf(FieldError.ADDRESS_NOT_READY), addressErrors(null, UiState.Loading))
    }

    @Test
    fun loadErrorIsNotReady() {
        assertEquals(setOf(FieldError.ADDRESS_NOT_READY), addressErrors(home, UiState.Error("Không có kết nối mạng.")))
    }
}
