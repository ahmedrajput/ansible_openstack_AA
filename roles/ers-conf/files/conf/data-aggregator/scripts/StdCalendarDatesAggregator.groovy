package se.seamless.ers.components.dataaggregator.aggregator

import groovy.util.logging.Log4j

import java.util.concurrent.TimeUnit

import org.apache.commons.lang.time.DateUtils
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


/**
 * Insert unique date values into std_calendar_dates_aggregation table.
 * Gets max date from table and inserts all unique dates from this 
 * last value till today.
 * 
 * @author Michał Podsiedzik
 */
@Log4j
@DynamicMixin
public class StdCalendarDatesAggregator extends AbstractAggregator
{
	static final def TABLE = "std_calendar_dates_aggregation"

	@Transactional
	@Scheduled(cron = '${StdCalendarDatesAggregator.cron:0 0/30 * * * ?}')
	public void aggregate()
	{
		def previous = jdbcTemplate.queryForObject("select max(calendarDate) from ${TABLE}", Date.class)

		def aggregation = []
		if(!previous)
		{
			log.debug("Last date does not exists, retuning now.")
			aggregation = [now()]
		}
		else if(previous < now())
		{
			log.debug("Last date ${previous} is ealier than now.")
			aggregation = DateUtils.addDays(previous, 1)..now()
		}
		updateAggregation(aggregation)
	}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows. Dates: ${aggregation}")
		if(aggregation)
		{
			jdbcTemplate.batchUpdate("INSERT INTO ${TABLE} (calendarDate) VALUES (?)", [
				setValues:
				{	ps, i ->
					ps.setDate(1, toSqlDate(aggregation[i]))
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}

	def now = { new Date().clearTime() }
}
