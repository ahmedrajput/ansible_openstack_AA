package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import org.springframework.jdbc.core.PreparedStatementSetter
import java.util.concurrent.TimeUnit
import java.math.BigDecimal;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

/**
 * Aggregates transaction rows per remoteAddress, eventType, userId .
 * @author M Asad Ullah Khan.
 */
@Log4j
@DynamicMixin
public class StdTerminalEventAggregator extends AbstractAggregator {

	@Value('${StdTerminalEventAggregator.batch:1000}')
	int limit
	
	def allowedTransactionTypes = [
		"CUSTOM_OPERATION_VOID_OPERATION"
	]
	@EqualsAndHashCode
	private static class Key {
	
		String resellerId
		Date eventTime
		String userId
		String eventType
		String remoteAddress
	}
	
	@Transactional
	@Scheduled(cron  = '${StdTerminalEventAggregator.cron:0 0/30 * * * ?}')
	public void aggregate() {

		def txtransactions = getTransactions(limit)
		if (!txtransactions) {
			log.info("No transactions returned")
			return
		}
		
		def matchedTransactions = txtransactions.findAll {
			it != null && it.resultCode == 0 && it.ersTransaction?.getResultMessage() != null && !it.ersTransaction.getResultMessage()?.contains("error") && allowedTransactionTypes.contains(it.getProfile())
				
		}
		if(matchedTransactions){
		 def transactions = matchedTransactions.collect{
	
			def eventTime = it.getEndTime()
			def tr = parser.parse(it.getJSON()).getAsJsonObject()
			def resultCode = tr.get("resultCode")?.getAsInt()
			def transactionType = it.getProfile()
			def userId = tr.get("principal")?.getAsJsonObject()?.get("user")?.getAsJsonObject()?.get("userId")?.getAsString()
			def profileId =  asString(tr, "profileId")
			def resellerId = tr.get("targetPrincipal")?.getAsJsonObject()?.get("resellerId")?.getAsString()
			def channel = asString(tr, "channel")
		    def eventType = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("EVENT_TYPE")?.getAsString()
		    def remoteAddress = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("remoteAddress")?.getAsString()
									
			[eventTime: eventTime, resellerId: resellerId, userId: userId, eventType: eventType,
					 remoteAddress: remoteAddress,
					 resultCode: resultCode]
			
		}.groupBy {
			new Key(eventTime: it.eventTime, resellerId: it.resellerId, userId: it.userId, eventType: it.eventType,
				 remoteAddress: it.remoteAddress)
		}.collect { key, list ->
			//aggregate total credit since activation, account balance, number of transaction,  last transaction
			[key : key, transactionCount: list.size()]
		}
		
		updateAggregation(transactions)
	}
		updateCursor(txtransactions)

		schedule(50, TimeUnit.MILLISECONDS)
	}
	
	private def updateAggregation = { aggregation ->
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation) {
			def sql = 
			"""INSERT INTO std_terminal_event_aggregation
			(resellerId, eventTime, userId, eventType, remoteAddress)
			VALUES (?, ?, ?, ?, ?)"""
			aggregation.each{ row ->
				def batchUpdate = jdbcTemplate.update(sql, [
						setValues: { ps ->
							ps.setString(1,row.key.resellerId.toString())
							ps.setTimestamp(2,toSqlTimestamp(row.key.eventTime))
							ps.setString(3, row.key.userId.toString())
							ps.setString(4, row.key.eventType.toString())
							ps.setString(5, row.key.remoteAddress.toString())
						}
				] as PreparedStatementSetter)
			}

		}
	}
	
	private def anyFieldAsList = {json,  ...fields ->
		fields.collect{field -> 
			json.get(field)
		}.findAll{it}
	}
}
