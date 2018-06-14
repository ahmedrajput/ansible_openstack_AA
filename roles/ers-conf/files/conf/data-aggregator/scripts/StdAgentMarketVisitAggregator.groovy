package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.util.logging.Log4j
import groovy.transform.ToString

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.ColumnMapRowMapper


/**
 * Aggregates details of AgentVisit and ResellerConfirmation Transactions.
 *
 * @author Rohini Chandrala
 */
@Log4j
@DynamicMixin
public class StdAgentMarketVisitAggregator extends AbstractAggregator {
	static final def TABLE = "std_agent_market_visit_aggregation"
	static final def RESELLER_VISIT_PROFILE = ["REGISTER_RESELLER_VISIT"]
	static final def CONFIRM_VISIT_PROFILE = ["CONFIRM_RESELLER_VISIT"]
	static final def CELLS_SQL = "select region as regionId from cells where id=?"
	static final def REGIONS_SQL = "select name as regionName, location as location, cluster as Zone from regions where id=?"

	@Value('${StdAgentMarketVisitAggregator.batch:1000}')
	int limit

	@Autowired
	@Qualifier("regionsDb")
	private JdbcTemplate regionsDb


	@Transactional
	@Scheduled(cron = '${StdAgentMarketVisitAggregator.cron:0 0/30 * * * ?}')
	public void aggregate() {
		def transactions = getTransactions(limit)
		if (transactions) {
			def agentVisit = findAllowedProfiles(transactions, RESELLER_VISIT_PROFILE)
					.findAll(successfulTransactions)
					.collect(transactionInfo)
					.findAll()


			log.debug("Agent Visit transactions count: "+agentVisit.size())
			def visitConfirmation = findAllowedProfiles(transactions, CONFIRM_VISIT_PROFILE)
					.findAll(successfulTransactions)
					.collect(visitconfirmed)
					.findAll()


			log.debug("Visit confirmation transactions count: "+visitConfirmation)
			updateAggregation(agentVisit, visitConfirmation)
			updateCursor(transactions)
			schedule()
		}
	}

	private def transactionInfo = {

		log.debug("Processing Agent Visit transaction: ${it}")
		def tr = parser.parse(it.getJSON()).getAsJsonObject()
		log.debug("TransactionData --- "+tr)

		def ersReference = asString(tr, "ersReference")
		log.debug("ERS Reference for agent visit "+ersReference)

		def date = toSqlTimestamp(new Date())

		def agentId = it.getErsTransaction()?.getPrincipal()?.getResellerId()
		log.debug("AgentId retrieved from transaction "+agentId)

		def agentMSISDN
		if(it.getErsTransaction()?.getPrincipal()?.getSubmittedPrincipalId()?.getType()?.equals("RESELLERMSISDN"))
			agentMSISDN = it.getErsTransaction().getPrincipal().getSubmittedPrincipalId().getId()

		def agentName = it.getErsTransaction()?.getPrincipal()?.getResellerName()

		def agentCellId = it.getErsTransaction()?.getTransactionProperties()?.get("INITIATOR_CELLID")

		def agentRegionInfo = getRegionInformation(agentCellId)

		def resellerId = it.getErsTransaction()?.getCustomerPrincipal()?.getResellerId()
		log.info("ResellerId retrieved from transaction "+resellerId)

		def resellerMSISDN
		if(it.getErsTransaction()?.getCustomerPrincipal()?.getSubmittedPrincipalId()?.getType()?.equals("RESELLERMSISDN"))
			resellerMSISDN = it.getErsTransaction().getCustomerPrincipal().getSubmittedPrincipalId().getId()

		def resellerName = it.getErsTransaction()?.getCustomerPrincipal()?.getResellerName()

		def resellerCellId = it.getErsTransaction()?.getTransactionProperties()?.get("RECEIVER_CELLID")

		def resellerRegionInfo = getRegionInformation(resellerCellId)

		def visitStatus = "No Visit"

		[
			ersReference          : ersReference,
			date                  : date,
			agentId				  : agentId,
			agentMSISDN        	  : agentMSISDN,
			agentName			  : agentName,
			agenCellId			  : agentCellId,
			agentRegion		  	  : agentRegionInfo?.getAt(0)?.regionName?:"Not Availalble",
			agentLocation	  	  : agentRegionInfo?.getAt(0)?.location?:"Not Available",
			agentZone		  	  : agentRegionInfo?.getAt(0)?.Zone?:"Not Available",
			resellerId            : resellerId,
			resellerMSISDN        : resellerMSISDN,
			resellerName		  : resellerName,
			resellerCellId		  : resellerCellId,
			resellerRegion		  : resellerRegionInfo?.getAt(0)?.regionName?:"Not Availalble",
			resellerLocation	  : resellerRegionInfo?.getAt(0)?.location?:"Not Available",
			resellerZone		  : resellerRegionInfo?.getAt(0)?.Zone?:"Not Available",
			visitStatus			  : visitStatus
		]
	}

	private def getRegionInformation(def cellid) {

		def regionId = null
		def regionInfo = null
		if(cellid != null)
		regionId = regionsDb.query(CELLS_SQL, [setValues : { ps ->
				use(TimeCategory) {
					ps.setString(1, cellid)
				}
			}] as PreparedStatementSetter, new ColumnMapRowMapper())

		if(regionId != null && regionId.size() != 1){
			log.error("Error in getting RegionId for given cellid: "+cellid)
			return null
		}

		regionInfo = regionsDb.query(REGIONS_SQL, [setValues : { ps ->
				use(TimeCategory) {
					ps.setString(1, regionId[0].regionId)
				}
			}] as PreparedStatementSetter, new ColumnMapRowMapper())
		
		if(regionInfo == null || regionInfo.size() != 1)
			log.error("Error in getting Reseller Region Information for given cellid: "+cellid)

		return regionInfo
	}

	private def visitconfirmed = {
		log.debug("Collecting transactions that has visit status as confirmed")

		log.debug("Processing Visit Confirmation transaction: ${it}")
		def tr = parser.parse(it.getJSON()).getAsJsonObject()

		log.debug("TransactionData --- "+tr)

		if(it.getErsTransaction()?.getTransactionProperties()?.get("VISIT_STATUS").equalsIgnoreCase("CONFIRMED")) {

			def ersReference = asString(tr, "ersReference")

			def resellerId = it.getErsTransaction()?.getPrincipal()?.getResellerId()
			log.debug("ResellerId retrieved from transaction "+resellerId)

			def visitStatus = it.getErsTransaction()?.getTransactionProperties()?.get("VISIT_STATUS")

			def agentVisitReference = it.getErsTransaction()?.getTransactionProperties()?.get("ERS_REF")

			log.debug("Agent Visit txe reference, retrieved from confirmation transaction: "+agentVisitReference)

			[
				ersReference          : ersReference,
				resellerId			  : resellerId,
				visitStatus			  : visitStatus,
				agentVisitReference	  : agentVisitReference
			]
		}
	}

	private def updateAggregation(List agentVisit, List visitConfirmation) {
		log.info("Aggregated into ${agentVisit.size()} rows.")
		if(agentVisit && agentVisit.size() != 0) {
			def sql = "INSERT INTO ${TABLE} (ersReference, aggregationDate, resellerId, resellerName, resellerMSISDN, resellerRegion, resellerLocation, resellerZone, agentMSISDN, agentName, agentRegion, agentLocation, agentZone, visitStatus) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues: { ps, i ->
					def row = agentVisit[i]
					def index = 0
					ps.setString(++index, row.ersReference)
					ps.setTimestamp(++index, row.date)
					ps.setString(++index, row.resellerId)
					ps.setString(++index, row.resellerName)
					ps.setString(++index, row.resellerMSISDN)
					ps.setString(++index, row.resellerRegion)
					ps.setString(++index, row.resellerLocation)
					ps.setString(++index, row.resellerZone)
					ps.setString(++index, row.agentMSISDN)
					ps.setString(++index, row.agentName)
					ps.setString(++index, row.agentRegion)
					ps.setString(++index, row.agentLocation)
					ps.setString(++index, row.agentZone)
					ps.setString(++index, row.visitStatus)
				},
				getBatchSize: { agentVisit.size() }
			] as BatchPreparedStatementSetter)
		}
		if(visitConfirmation && visitConfirmation.size() !=0){
			def sql = "UPDATE ${TABLE} set visitStatus='Visit' where ersReference=?"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues: { ps, i ->
					def row = visitConfirmation[i]
					ps.setString(1, row.agentVisitReference)
				},
				getBatchSize: { visitConfirmation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}
