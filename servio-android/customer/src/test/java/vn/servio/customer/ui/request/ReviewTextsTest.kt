package vn.servio.customer.ui.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.customer.data.model.ScheduleType
import vn.servio.customer.ui.request.ReviewTexts.ExpiryNote
import vn.servio.customer.ui.request.ReviewTexts.Requirement
import vn.servio.customer.ui.request.ReviewTexts.TimeLine
import java.time.LocalDate

class ReviewTextsTest {

    private val free = certificateState(categoryRequires = false, userChoice = false)

    @Test
    fun nowTimeLine() {
        assertEquals(TimeLine.Now, ReviewTexts.timeLine(RequestDraft(scheduleType = ScheduleType.NOW)))
    }

    @Test
    fun scheduledTimeLineKeepsDayAndSlot() {
        val date = LocalDate.of(2026, 10, 17)
        val draft = RequestDraft(scheduleType = ScheduleType.SCHEDULED, scheduledDate = date, scheduledHour = 14)
        assertEquals(TimeLine.Scheduled(date, 14), ReviewTexts.timeLine(draft))
    }

    @Test
    fun scheduledWithoutSlotHasNoLine() {
        val draft = RequestDraft(scheduleType = ScheduleType.SCHEDULED, scheduledDate = LocalDate.of(2026, 10, 17))
        assertNull(ReviewTexts.timeLine(draft))
    }

    /** create_request_weekday_full: Thứ Hai … Thứ Bảy, Chủ Nhật. 17/10/2026 is a Saturday. */
    @Test
    fun weekdayIndexFromMondayToSunday() {
        val monday = LocalDate.of(2026, 10, 12)
        assertEquals((0..6).toList(), (0L..6L).map { ReviewTexts.weekdayIndex(monday.plusDays(it)) })
        assertEquals(5, ReviewTexts.weekdayIndex(LocalDate.of(2026, 10, 17)))
        assertEquals(6, ReviewTexts.weekdayIndex(LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun budgetRangeIsGrouped() {
        val draft = RequestDraft(quoteByPartner = false, budgetMin = 300_000, budgetMax = 500_000)
        assertEquals("300.000 – 500.000", ReviewTexts.budgetRange(draft))
    }

    @Test
    fun quoteByPartnerHasNoRangeEvenWithNumbersKept() {
        assertNull(ReviewTexts.budgetRange(RequestDraft(quoteByPartner = true, budgetMin = 300_000, budgetMax = 500_000)))
        assertNull(ReviewTexts.budgetRange(RequestDraft()))
    }

    @Test
    fun noRequirement() {
        assertEquals(emptyList<Requirement>(), ReviewTexts.requirements(RequestDraft(), free))
        assertEquals(
            "Không yêu cầu thêm · bán kính 10 km",
            ReviewTexts.requirementsLine(emptyList(), none = "Không yêu cầu thêm", radius = "bán kính 10 km"),
        )
    }

    @Test
    fun oneRequirement() {
        val draft = RequestDraft(requireExperience = true)
        assertEquals(listOf(Requirement.EXPERIENCE), ReviewTexts.requirements(draft, free))
        assertEquals(
            "Từ 3 năm kinh nghiệm · bán kính 10 km",
            ReviewTexts.requirementsLine(listOf("Từ 3 năm kinh nghiệm"), none = "Không yêu cầu thêm", radius = "bán kính 10 km"),
        )
    }

    @Test
    fun threeRequirementsInOrder() {
        val draft = RequestDraft(requireExperience = true, requireCertificate = true, requireHighRating = true)
        val ticked = certificateState(categoryRequires = false, userChoice = true)
        assertEquals(
            listOf(Requirement.EXPERIENCE, Requirement.CERTIFICATE, Requirement.HIGH_RATING),
            ReviewTexts.requirements(draft, ticked),
        )
        assertEquals(
            "Từ 3 năm kinh nghiệm · Có chứng chỉ nghề · Đánh giá từ 4,5 sao · bán kính 20 km",
            ReviewTexts.requirementsLine(
                listOf("Từ 3 năm kinh nghiệm", "Có chứng chỉ nghề", "Đánh giá từ 4,5 sao"),
                none = "Không yêu cầu thêm",
                radius = "bán kính 20 km",
            ),
        )
    }

    @Test
    fun certificateFollowsTheService() {
        val locked = certificateState(categoryRequires = true, userChoice = false)
        assertEquals(listOf(Requirement.CERTIFICATE), ReviewTexts.requirements(RequestDraft(), locked))
    }

    @Test
    fun expiryNote() {
        assertEquals(ExpiryNote.NOW_TWO_HOURS, ReviewTexts.expiryNote(ScheduleType.NOW))
        assertEquals(ExpiryNote.SCHEDULED_DAY_OR_START, ReviewTexts.expiryNote(ScheduleType.SCHEDULED))
    }
}
