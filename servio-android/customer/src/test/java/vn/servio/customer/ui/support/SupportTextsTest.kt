package vn.servio.customer.ui.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.servio.customer.R
import vn.servio.customer.data.model.SupportContact
import vn.servio.customer.data.repository.serverConfig
import vn.servio.customer.ui.order.OrderTexts
import vn.servio.customer.ui.request.MoneyInput
import vn.servio.customer.ui.support.SupportTexts.Arg
import java.io.File

class SupportTextsTest {

    private val strings = File("src/main/res/values/strings.xml").readText()

    /** A string as aapt builds it: &#160; decoded. */
    private fun template(name: String) =
        Regex("<string name=\"$name\">(.*?)</string>").find(strings)!!.groupValues[1].replace("&#160;", OrderTexts.NBSP.toString())

    private val names = mapOf(
        R.string.support_faq_fee_answer to "support_faq_fee_answer",
        R.string.support_faq_payment_answer to "support_faq_payment_answer",
        R.string.support_faq_auto_answer to "support_faq_auto_answer",
        R.string.support_faq_dispute_answer to "support_faq_dispute_answer",
    )

    /** What SupportFragment shows: a fee as OrderFormat.money ("20.000 đ" held together), the rest as it is. */
    private fun answer(faq: SupportTexts.Faq): String {
        val args = faq.args.map { arg ->
            when (arg) {
                is Arg.Money -> OrderTexts.keepTogether(template("quote_price").replace("%1\$s", MoneyInput.format(arg.amount)))
                is Arg.Count -> arg.value
                is Arg.Text -> arg.value
            }
        }
        return String.format(template(names.getValue(faq.answer)), *args.toTypedArray())
    }

    private val nbsp = OrderTexts.NBSP

    @Test
    fun `the answers of the Figma, with the figures of 107`() {
        val answers = SupportTexts.faqs(serverConfig()).map(::answer)
        assertEquals(
            listOf(
                "Miễn phí trước khi đối tác di chuyển. Khi đối tác đang di chuyển: 20.000${nbsp}đ. " +
                    "Khi đối tác đã đến: 30.000${nbsp}đ hoặc 20% giá báo, lấy mức cao hơn.",
                "Bạn trả tiền mặt trực tiếp cho đối tác rồi bấm xác nhận hoàn thành trong app.",
                "Nếu bạn không xác nhận hoặc khiếu nại trong 24${nbsp}giờ sau khi đối tác báo xong, đơn tự hoàn thành.",
                "Trong lúc đối tác đang làm và đến 72${nbsp}giờ sau khi đơn hoàn thành.",
            ),
            answers,
        )
    }

    @Test
    fun `other figures from AW-15 change the sentences`() {
        val config = serverConfig(
            cancellationFeeAfterOnTheWay = 25_000,
            cancellationFeeAfterArrivedMin = 40_000,
            cancellationFeeAfterArrivedPercent = 12.5,
            autoConfirmHours = 12,
            disputeWindowHours = 48,
        )
        val answers = SupportTexts.faqs(config).map(::answer)
        assertEquals(
            "Miễn phí trước khi đối tác di chuyển. Khi đối tác đang di chuyển: 25.000${nbsp}đ. " +
                "Khi đối tác đã đến: 40.000${nbsp}đ hoặc 12,5% giá báo, lấy mức cao hơn.",
            answers[0],
        )
        assertEquals("Nếu bạn không xác nhận hoặc khiếu nại trong 12${nbsp}giờ sau khi đối tác báo xong, đơn tự hoàn thành.", answers[2])
        assertEquals("Trong lúc đối tác đang làm và đến 48${nbsp}giờ sau khi đơn hoàn thành.", answers[3])
    }

    @Test
    fun `the hotline card hides what AW-15 left empty`() {
        assertEquals(SupportContact("1900 1234", "8:00–21:00 hằng ngày"), SupportTexts.contact(SupportContact(" 1900 1234 ", "8:00–21:00 hằng ngày")))
        assertEquals(SupportContact(null, "8:00–21:00 hằng ngày"), SupportTexts.contact(SupportContact(null, "8:00–21:00 hằng ngày")))
        assertEquals(SupportContact("1900 1234", null), SupportTexts.contact(SupportContact("1900 1234", " ")))
        assertNull(SupportTexts.contact(SupportContact(null, null)))
        assertNull(SupportTexts.contact(SupportContact("", "")))
    }

    @Test
    fun `the dialer gets digits only`() {
        assertEquals("19001234", SupportTexts.dialNumber("1900 1234"))
        assertEquals("+842363123456", SupportTexts.dialNumber("+84 236.3123.456"))
    }
}
