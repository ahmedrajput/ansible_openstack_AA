package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.util.logging.Log4j
import se.seamless.ers.components.dataaggregator.consumer.SuggestedStockServiceConsumer

import java.util.List

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


@Log4j
@DynamicMixin
public class StdLowStockThresholdAggregator extends SuggestedStockServiceConsumer {
	static final def TABLE = "std_low_stock_alert_aggregation"

	static final def ACCOUNTSSQL = "select accountId as accountId, balance as balance from accounts.accounts where status='Active' and accountId=?"

	static final def AGENT_SUP_LOC = "select agent_msisdn as agentMSISDN, agent_name as agentName from agent_supervisor_loc_map where agent_region=? and agent_location=? and agent_cluster=?"

	static final def RESELLERSQL = """
		SELECT c.tag AS resellerId, 
		dev.address AS resellerMSISDN,
        c.name AS resellerName,
        c.chain_store_id AS resellerNationalId,
        rt.id AS resellerType,
        co.name AS resellerContractId,
        c.reseller_path AS resellerPath,
        c.rgroup AS resellerZone,
		c.last_modified,
		c.type_key as resellerLevel,
		c.rgroup as resellerRegion,
		c.subrgroup as resellerLocation,
		c.subsubrgroup as resellerCluster
        FROM commission_receivers c
        LEFT JOIN extdev_devices as dev
	    ON (dev.owner_key = c.receiver_key)
        LEFT JOIN commission_contracts co ON (co.contract_key = c.contract_key)
        LEFT JOIN reseller_types rt ON (rt.type_key = c.type_key)
        LEFT JOIN pay_prereg_accounts pa ON (pa.owner_key=c.receiver_key)
		"""

	static final def dateFormat = "yyyy-MM-dd HH:mm:ss"

	private def stockReportRecords

	@Autowired
	@Qualifier("accounts")
	private JdbcTemplate accounts

	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill


	@Transactional
	@Scheduled(cron  = '${StdLowStockThresholdAggregator.cron:0 0/30 * * * ?}')
	public void aggregate() {
		def date = getDate()
		def threshold
		def accountTransactions
		def agentInformation
		def stockReportMap
		stockReportRecords = new ArrayList()
		def resellers = refill.query(RESELLERSQL, [setValues: { ps ->
				use(TimeCategory) {
				}
			}] as PreparedStatementSetter, new ColumnMapRowMapper())

		log.debug("Got ${resellers.size()} resellers from refill.")

		for (resellerRecord in resellers) {

			if(resellerRecord.resellerMSISDN == null || resellerRecord.resellerMSISDN.equals(""))
				continue
			threshold = getThresholdStock(resellerRecord.resellerMSISDN)
			if(threshold != null && !threshold.isNaN()){
				accountTransactions = accounts.query(ACCOUNTSSQL, [setValues: { ps ->
						use(TimeCategory) {
							ps.setString(1, resellerRecord.resellerId)
						}
					}] as PreparedStatementSetter, new ColumnMapRowMapper())

				log.debug("Received ${accountTransactions.size()} account transactions from accounts")

				agentInformation = refill.query(AGENT_SUP_LOC, [setValues : { ps ->
						use(TimeCategory) {
							ps.setString(1, resellerRecord.resellerRegion)
							ps.setString(2, resellerRecord.resellerLocation)
							ps.setString(3, resellerRecord.resellerCluster)
						}
					}] as PreparedStatementSetter, new ColumnMapRowMapper())

				log.debug("Received ${agentInformation.size()} agent record/s from table agent_supervisor_loc_map")

				if(accountTransactions.size() == 1 && agentInformation.size() == 1) {
					if(threshold > accountTransactions[0].balance) {
						stockReportMap = [
							aggregationDate : toSqlDate(date),
							resellerId : resellerRecord.resellerId,
							resellerMSISDN : resellerRecord.resellerMSISDN,
							resellerName : resellerRecord.resellerName,
							agentMSISDN : agentInformation[0].agentMSISDN,
							agentName : agentInformation[0].agentName,
							resellerRegion : resellerRecord.resellerRegion,
							resellerLocation : resellerRecord.resellerLocation,
							resellerCluster : resellerRecord.resellerCluster,
							stockThreshold : threshold,
							stockLevel : accountTransactions[0].balance
						]
						stockReportRecords.add(stockReportMap)
					}
					else if(threshold == null){
						log.debug("Call to Threshold Stock service returned null")
					}
				}
				else if(accountTransactions.size() > 1)
					log.error("Two or more accounts exists with same reseller Id " + resellerRecord.resellerId + ", so excluded the Reseller");
				else if(agentInformation.size() > 1)
					log.error("Two or more agents belong to same "+resellerRecord.resellerRegion +" : " + resellerRecord.resellerLocation + " : " + resellerRecord.resellerCluster)
			}
			else
				log.debug("Excluded reseller " + resellerRecord.resellerId + "from the report")
		}
		updateAggregation()
	}

	private def updateAggregation() {
		log.debug("Aggregated into ${stockReportRecords.size()} rows.")
		if(stockReportRecords) {
			def sql = "INSERT into ${TABLE} (aggregationDate, resellerMSISDN, resellerName, agentMSISDN, agentName, resellerRegion, resellerLocation, resellerCluster, stockThreshold, stockLevel, resellerId) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues: {	ps, i ->
					def row = stockReportRecords[i]
					ps.setTimestamp(1, toSqlTimestamp(row.aggregationDate))
					ps.setString(2, row.resellerMSISDN)
					ps.setString(3, row.resellerName)
					ps.setString(4, row.agentMSISDN)
					ps.setString(5, row.agentName)
					ps.setString(6, row.resellerRegion)
					ps.setString(7, row.resellerLocation)
					ps.setString(8, row.resellerCluster)
					ps.setBigDecimal(9, row.stockThreshold)
					ps.setBigDecimal(10, row.stockLevel)
					ps.setString(11,row.resellerId)
				},
				getBatchSize: { stockReportRecords.size() }
			] as BatchPreparedStatementSetter)
		}
	}

	private getDate = {
		def cursor = getCursor()
		def date = toSqlDate(new Date())
		if(cursor) {
			date = Date.parse(dateFormat, cursor);
			use(TimeCategory) { date = toSqlDate(date) }
		}
		else{
			use(TimeCategory) { date = toSqlDate(date) }
		}
		return date
	}
}