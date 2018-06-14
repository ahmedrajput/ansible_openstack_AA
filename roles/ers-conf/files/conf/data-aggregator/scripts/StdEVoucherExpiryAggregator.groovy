
package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import java.util.Date;
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import com.seamless.ers.interfaces.platform.clients.transaction.model.ERSTransactionResultCode
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler

 /**
 * Aggregates expiry vouchers by taking from dwa_items 
 * It dump data to `dataaggregator`.`std_evoucher_expiry_aggregation`
 * @author Koyel Mahata
 */
@Log4j
@DynamicMixin
public class StdEVoucherExpiryAggregator extends AbstractAggregator
{
	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill
	
	static final def dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
	
	static final def TABLE = "std_evoucher_expiry_aggregation"
	
	static final def expiryVoucherSql ="select count(item_key) as count , FORMAT(sum(in_price_value)/100,2) as totalExpiryVouchers, in_price_currency, status,FORMAT(in_price_value/100,2) as denomination from dwa_items where expiry_date >= ? and expiry_date < NOW() group by status, denomination"
	
	@Transactional
	@Scheduled(cron = '${StdEVoucherExpiryAggregator.cron:0/1 * * * * ?}')
	public void aggregate(){
		
		log.info("Started StdEVoucherExpiryAggregator aggregation ...")
		def date = getDate()	
		log.info(" cursor date:"+date)
		def expiryVouchersList = refill.query(expiryVoucherSql, [setValues: { ps ->
				ps.setTimestamp(1, date)
			}] as PreparedStatementSetter, new ColumnMapRowMapper())
		
		log.info("Got StdEVoucherExpiryAggregator ${expiryVouchersList.size()}  from refill.")
		log.info("Got StdEVoucherExpiryAggregatorfrom refill." +expiryVouchersList)
		if(expiryVouchersList) {
			
			 def sql = "INSERT INTO ${TABLE} (date,count,amount,currency,status,denomination) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE count = count+VALUES(count),amount=amount+VALUES(amount)"
			jdbcTemplate.batchUpdate(sql,[
				setValues: { ps, i ->
					ps.setDate(1,toSqlDate(new Date()))
					ps.setBigDecimal(2,expiryVouchersList[i].count)
					ps.setBigDecimal(3,new BigDecimal(expiryVouchersList[i].totalExpiryVouchers.replaceAll(",","")))
					ps.setString(4,expiryVouchersList[i].in_price_currency)
					ps.setInt(5,expiryVouchersList[i].status)
					ps.setBigDecimal(6,new BigDecimal(expiryVouchersList[i].denomination.replaceAll(",","")))
				},
				getBatchSize: { expiryVouchersList.size() }
				] as BatchPreparedStatementSetter)
			
		}
		//update cursor with the modified cursor date
		updateCursor(new Date().format(dateFormat))
		log.debug("Updated cursor date:"+date.format(dateFormat))
	}
	// get cursor date ,if it exist-> update date with that cursor date
	private getDate = {
		def cursor = getCursor()
		
		def date = toSqlTimestamp(new Date())
		if(cursor) {
			date = Date.parse(dateFormat, cursor);
			date = toSqlTimestamp(date)
		}
		else{
		use(TimeCategory) {
				date = toSqlTimestamp(date - 90.days)
		}
		}
		return date
	}
	
}
