package vn.servio.customer.ui.review

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.servio.core.network.ApiResult
import vn.servio.customer.R
import vn.servio.customer.data.model.OrderAssignment
import vn.servio.customer.data.model.OrderDetail
import vn.servio.customer.data.model.OrderStatus
import vn.servio.customer.data.model.PendingReview
import vn.servio.customer.data.model.SubmittedReview
import vn.servio.customer.data.repository.FakeOrderRepository
import vn.servio.customer.data.repository.FakeReviewRepository
import vn.servio.customer.ui.review.ReviewForm.Criterion
import vn.servio.customer.ui.review.SubmitReviewViewModel.Event

@OptIn(ExperimentalCoroutinesApi::class)
class SubmitReviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val row = PendingReview("o1", "a1", "OD2611180010", "Đỗ Quốc Bảo", "2026-11-18T08:00:00Z", "2026-11-23T08:00:00Z")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun server() = FakeOrderRepository().apply {
        order = ApiResult.Success(OrderDetail("o1", "OD2611180010", OrderStatus.COMPLETED, assignments = listOf(OrderAssignment("a1", OrderStatus.COMPLETED, isPrimary = true))))
    }

    private fun TestScope.viewModel(reviews: FakeReviewRepository, events: MutableList<Event>, handle: SavedStateHandle = SavedStateHandle(mapOf("orderId" to "o1", "assignmentId" to "a1"))) =
        SubmitReviewViewModel(handle, server(), reviews).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
            vm.load()
            advanceUntilIdle()
        }

    @Test
    fun `an assignment not in 100 closes the form`() = runTest(dispatcher) {
        val vm = viewModel(FakeReviewRepository(), mutableListOf())
        assertTrue(vm.state.value.closed)
        assertFalse(vm.state.value.canSubmit)
    }

    @Test
    fun `send needs the overall stars, then sends once and leaves with the message`() = runTest(dispatcher) {
        val reviews = FakeReviewRepository().apply {
            pending = ApiResult.Success(listOf(row))
            submits += ApiResult.Success(SubmittedReview("r1", "o1", "a1", 5))
        }
        val events = mutableListOf<Event>()
        val vm = viewModel(reviews, events)
        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        assertTrue(reviews.submitCalls.isEmpty())

        vm.setRating(5)
        vm.setCriterion(Criterion.PUNCTUALITY, 5)
        vm.setCriterion(Criterion.ATTITUDE, 4)
        vm.setComment("  Làm nhanh  ")
        assertTrue(vm.state.value.canSubmit)
        vm.submit()
        vm.submit()
        advanceUntilIdle()
        assertEquals(1, reviews.submitCalls.size)
        val body = reviews.submitCalls.single()
        assertEquals(5, body.rating)
        assertEquals(5, body.punctualityRating)
        assertEquals(4, body.attitudeRating)
        assertEquals(null, body.qualityRating)
        assertEquals("Làm nhanh", body.comment)
        assertEquals(listOf<Event>(Event.Leave(R.string.review_sent)), events)
    }

    @Test
    fun `stars and comment survive a new view model on the same saved state`() = runTest(dispatcher) {
        val reviews = FakeReviewRepository().apply { pending = ApiResult.Success(listOf(row)) }
        val handle = SavedStateHandle(mapOf("orderId" to "o1", "assignmentId" to "a1"))
        val vm = viewModel(reviews, mutableListOf(), handle)
        vm.setRating(4)
        vm.setCriterion(Criterion.QUALITY, 3)
        vm.setComment("Ổn")
        val restored = viewModel(reviews, mutableListOf(), handle)
        assertEquals(4, restored.state.value.rating)
        assertEquals(mapOf(Criterion.QUALITY to 3), restored.state.value.criteria)
        assertEquals("Ổn", restored.state.value.comment)
    }

    @Test
    fun `95 errors - window closed closes the form, already sent leaves, others keep the form`() = runTest(dispatcher) {
        listOf(
            "REVIEW_WINDOW_CLOSED" to Event.Message(res = R.string.review_window_closed),
            "REVIEW_ALREADY_SUBMITTED" to Event.Leave(R.string.review_already),
            "NETWORK_ERROR" to Event.Message(text = "Không có kết nối mạng"),
        ).forEach { (code, expected) ->
            val reviews = FakeReviewRepository().apply {
                pending = ApiResult.Success(listOf(row))
                submits += ApiResult.Failure(code, "Không có kết nối mạng")
            }
            val events = mutableListOf<Event>()
            val vm = viewModel(reviews, events)
            vm.setRating(3)
            vm.setComment("abc")
            vm.submit()
            advanceUntilIdle()
            assertEquals(listOf(expected), events)
            assertFalse(vm.state.value.sending)
            assertEquals(code == "REVIEW_WINDOW_CLOSED", vm.state.value.closed)
            assertEquals(3, vm.state.value.rating)
            assertEquals("abc", vm.state.value.comment)
        }
    }
}
