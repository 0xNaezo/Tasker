package app.tasker.core.parser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Micro-benchmark of tech plan §9.1/§9.7: a typical line must parse in well under 2 ms, because parsing runs while the user
 * types. The bound is generous for CI machines; on a laptop the average is a few tens of microseconds.
 */
class ParserPerformanceTest {
    private val typical = listOf(
        "пт до 18:00 сдать отчёт #client M",
        "завтра позвонить",
        "дойти до магазина",
        "к врачу в чт",
        "сегодня разобрать почту S",
        "когда-нибудь выучить Rust @учёба",
        "уже сделал ревью PR",
        "созвон завтра в 15",
        "сегодня до 18:00 отправить счёт",
        "deadline fri 5pm send invoice",
        "у п'ятницю до 12:00 оплатити рахунок",
        "через 3 дня продлить подписку на https://example.com/billing",
        "на следующей неделе записаться к стоматологу @здоровье",
        "next fri by 6pm submit the quarterly report #work L",
        "12 октября конференция, купить билеты до 30.09",
        "написать ivan@mail.ru про договор до завтра 12:00",
        "in 2 weeks renew the domain example.com",
        "наступного тижня зустріч з командою о 10 ранку",
        "купить молоко, хлеб и яйца",
        "Сделать презентацию к понедельнику, 20 слайдов M",
    )

    @Test
    fun typicalLinesParseWellUnderTwoMilliseconds() {
        val parser = InputParser()
        val context = ParserFixtures.context()
        var sink = 0
        repeat(WARM_UP_ROUNDS) { for (line in typical) sink += parser.parse(line, context).fields.size }
        val started = System.nanoTime()
        repeat(MEASURED_ROUNDS) { for (line in typical) sink += parser.parse(line, context).fields.size }
        val averageMillis = (System.nanoTime() - started) / 1e6 / (MEASURED_ROUNDS * typical.size)
        assertThat(sink).isGreaterThan(0)
        assertWithMessage("average parse time of a typical line, ms").that(averageMillis).isLessThan(MAX_AVERAGE_MILLIS)
    }

    private companion object {
        const val WARM_UP_ROUNDS = 500
        const val MEASURED_ROUNDS = 2000
        const val MAX_AVERAGE_MILLIS = 2.0
    }
}
