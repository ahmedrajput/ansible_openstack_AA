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
 * In general the script should run once a day. It makes resellers with its detail
 * <p>
 * It reads from Refill.commission_receivers and writed to `dataaggregator`.`resellers`
 * <p>
 * Before write aggregation for current day is deleted from table;
 *
 * @author Tirthankar Mitra
 */

@Log4j
@DynamicMixin
public class StdPromotionSystemResellersAggregator extends AbstractAggregator {

	static final def dateFormat = "yyyy-MM-dd"
	
	static final def RESELLERSQL = """
		SELECT c.tag AS resellerId, 
		dev.address AS resellerMSISDN,
        c.name AS resellerName,
        c.chain_store_id AS resellerNationalId,
        rt.id AS resellerType,
        co.name AS resellerContractId,
        c.reseller_path AS resellerPath,
        c.rgroup AS resellerZone,
		c.subrgroup AS resellerSubrgroup,
        c.subsubrgroup AS resellerSubSubrgroup,
		c.last_modified,
		c.type_key as resellerLevel,
       (case c.status when '0' then 'Active' when '1' then 'InActive' when '2' then 'Blocked' when '3' then 'Frozen' else 'Disabled' end) as resellerStatus
        FROM commission_receivers c
        LEFT JOIN extdev_devices as dev
	    ON (dev.owner_key = c.receiver_key)
        LEFT JOIN commission_contracts co ON (co.contract_key = c.contract_key)
        LEFT JOIN reseller_types rt ON (rt.type_key = c.type_key)
        LEFT JOIN pay_prereg_accounts pa ON (pa.owner_key=c.receiver_key)
		WHERE c.last_modified > ?
		"""
		
	//"select tag, time_first_terminal_activation from commission_receivers where time_first_terminal_activation >= ?"
	static final def RESELLERTABLE = "resellers"
	
	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill
	
	@Transactional
	@Scheduled(cron = '${StdPromotionSystemResellersAggregator.cron:0 0/30 * * * ?}')
	public void aggregate() {
		
		def date = getDate()
		
		def resellers = refill.query(RESELLERSQL, [setValues: { ps ->
				use(TimeCategory) {
					ps.setDate(1, toSqlDate(date))
				}
			}] as PreparedStatementSetter, new ColumnMapRowMapper())
		
		log.info("Got ${resellers.size()} resellers from refill.")
		if(resellers) {
			jdbcTemplate.batchUpdate("""insert into 
				resellers (resellerId, resellerMSISDN, resellerName, 
				resellerNationalId, resellerLevel, resellerType, resellerContractId, 
				resellerPath, resellerZone, resellerSubrgroup, resellerSubSubrgroup, parentResellerId, resellerStatus) 
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) 
				ON DUPLICATE KEY UPDATE 
				resellerMSISDN=VALUES(resellerMSISDN),
				resellerName=VALUES(resellerName),
				resellerNationalId=VALUES(resellerNationalId),
				resellerLevel=VALUES(resellerLevel),
				resellerType=VALUES(resellerType),
				resellerContractId=VALUES(resellerContractId),
				resellerPath=VALUES(resellerPath),
				resellerZone=VALUES(resellerZone),
				resellerSubrgroup=VALUES(resellerSubrgroup),
				resellerSubSubrgroup=VALUES(resellerSubSubrgroup),
				parentResellerId=VALUES(parentResellerId),
				resellerStatus=VALUES(resellerStatus)
				""",  [
				setValues: { ps, i ->
					
					String parentResellerId = resellers[i].resellerPath
					int lastIndexOfFirstOccurance = parentResellerId.lastIndexOf("/")
					
					if(lastIndexOfFirstOccurance>-1)
					{
						String parentSubString = parentResellerId.substring(0,lastIndexOfFirstOccurance)
				
						int lastIndexOfSecondOcurance =parentSubString.lastIndexOf("/")
						
						if(lastIndexOfSecondOcurance<0)
						{	
							parentResellerId = parentSubString
						}
						else
						{
							parentResellerId = parentSubString.substring(lastIndexOfSecondOcurance+1)
						}
					}
					else
					{
						parentResellerId = ""
					}
					
					ps.setString(1, resellers[i].resellerId)
					ps.setString(2, resellers[i].resellerMSISDN)
					ps.setString(3, resellers[i].resellerName)
					ps.setString(4, resellers[i].resellerNationalId)
					ps.setInt(5, resellers[i].resellerLevel) /** set reseller level **/
					ps.setString(6, resellers[i].resellerType)
					ps.setString(7, resellers[i].resellerContractId)
					ps.setString(8, resellers[i].resellerPath)
					ps.setString(9, resellers[i].resellerZone)
					ps.setString(10, resellers[i].resellerSubrgroup)
					ps.setString(11, resellers[i].resellerSubSubrgroup)
					ps.setString(12, parentResellerId)
					ps.setString(13, resellers[i].resellerStatus)
					date = resellers[i].last_modified
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
