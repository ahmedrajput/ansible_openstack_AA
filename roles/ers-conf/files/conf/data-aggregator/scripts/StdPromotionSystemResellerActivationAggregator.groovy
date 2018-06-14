package se.seamless.ers.components.dataaggregator.aggregator

import java.sql.ResultSet;
import java.util.Date;

import groovy.swing.binding.JListProperties.*
import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode;
import groovy.transform.ToString;
import groovy.util.logging.Log4j

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


/**
 * Script runs once a day. It makes  resellers with reseller activation date
 * <p>
 * It reads from Refill.commission_receivers and writed to dataaggregator.promotionsystsem_transaction_statistics_aggregation.
 * <p>
 * Before write aggregation for current day is deleted from table;
 *
 * @author Sk Safiruddin
 */
@Log4j
@DynamicMixin
public class StdPromotionSystemResellerActivationAggregator extends AbstractAggregator {

	static final def dateFormat = "yyyy-MM-dd"
	
	static final def RESELLERACTIVATIONSQL = "select tag, time_first_terminal_activation from commission_receivers where time_first_terminal_activation >= ?"
	static final def RESELLERACTIVATIONTABLE = "promotionsystem_reseller_activations"
	
	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill
	
	@Transactional
	@Scheduled(cron = '${StdPromotionSystemResellerActivationAggregator.cron:0 0/30 * * * ?}')
	public void aggregate() {
		
		def date = getDate()
		
		def resellers = refill.query(RESELLERACTIVATIONSQL, [setValues: { ps ->
				use(TimeCategory) {
					ps.setDate(1, toSqlDate(date))
				}
			}] as PreparedStatementSetter, new ColumnMapRowMapper())
		
		log.info("Got ${resellers.size()} activate reseller from refill.")
		if(resellers) {
			jdbcTemplate.batchUpdate("insert into "+RESELLERACTIVATIONTABLE+" (resellerId, activationDate) values (?, ?) ON DUPLICATE KEY UPDATE activationDate=VALUES(activationDate)",  [
				setValues: { ps, i ->
					ps.setString(1, resellers[i].tag)
					ps.setTimestamp(2, toSqlTimestamp(resellers[i].time_first_terminal_activation))
					date = resellers[i].time_first_terminal_activation
				},
				getBatchSize: { resellers.size() }
			] as BatchPreparedStatementSetter)
		}
		
		updateCursor(date.format(dateFormat))
	}
	
	private getDate = {
		def cursor = getCursor()
		def date = toSqlDate(new Date().clearTime())
		if(cursor) {
			date = Date.parse(dateFormat, cursor);
			use(TimeCategory) {
				date = toSqlDate(date)
			}
		}
		else{
			use(TimeCategory) {
				date = toSqlDate(date - 10.years)
			}
		}
		return date
	}
}
