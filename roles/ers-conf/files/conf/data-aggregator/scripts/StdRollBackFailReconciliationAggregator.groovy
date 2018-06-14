package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit
import java.text.DecimalFormat;
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate


/**
 * Aggregates transaction rows per SELL_DATE, RESULT_CODE and TRANSACTION_TYPE
 * calculate and manages roll back failed transactions balances
 * @author Muhammad Asad Ullah Khan
 */
@Log4j
@DynamicMixin
public class StdRollBackFailReconciliationAggregator extends AbstractAggregator {
	
	@Autowired 
	@Qualifier("accounts")
	private JdbcTemplate accounts
	
	@Value('${StdRollBackFailReconciliationAggregator.batch:1000}')
	int limit
	
	def allowedTransactionTypes = [
		"CREDIT_TRANSFER",
		"TOPUP",
        "PRODUCT_RECHARGE"
	]
	
	@EqualsAndHashCode
	@ToString
	private static class Key {
		Date sell_Date
		String transaction_id
		String receiverId
		String senderResellerId
		String profileId
		String resultCode

		int compareTo(Key other) {
			
		sell_Date <=> other.sell_Date ?: transaction_id <=> transaction_id ?: senderResellerId <=> other.senderResellerId ?: receiverId <=> other.receiverId ?: profileId <=> other.profileId ?: resultCode <=> resultCode
	
		}
	}
	
	@Transactional
	@Scheduled(cron  = '${StdRollBackFailReconciliationAggregator.cron:0 0/30 * * * ?}')
	public void aggregate()
	{
		def txTransactions;
		
		
		log.info("StdRollBackFailReconciliationAggregator feating transaction")
		use(TimeCategory) {
			txTransactions = getTransactions(new Date() - 1.minute,limit)
		}
		
		if (!txTransactions) {
			log.info("No transactions returned")
			return
		}
		def matchedTransactions = txTransactions.findAll {
			it != null && it.resultCode != 0 && it.resultCode != 1 && allowedTransactionTypes.contains(it.getProfile())
		}
		
		if (matchedTransactions) {
			def transactions = matchedTransactions.collect{
				def sell_Date=it.getEndTime()
			    def resultCode = it.resultCode
				def tr = parser.parse(it.getJSON()).getAsJsonObject()
				//TODO
				def transaction_id =  tr.get("ersReference")?.getAsString()
				def profileId = tr.get("profileId")?.getAsString()
				def senderPrincipalJsonObject = tr.get("senderPrincipal")?.getAsJsonObject()
				def senderResellerId;
				if (senderPrincipalJsonObject) 
				{
					senderResellerId =  asString(senderPrincipalJsonObject, "resellerId")
					
				}
				def receiverPrincipalJsonObject = tr.get("receiverPrincipal")?.getAsJsonObject()
				def receiverId;
				if (receiverPrincipalJsonObject ) 
				{
					receiverId =  asString(receiverPrincipalJsonObject, "resellerId")
				}
				
				[sell_Date: sell_Date, transaction_id: transaction_id, receiverId : receiverId, senderResellerId: senderResellerId, profileId: profileId, resultCode: resultCode]
			}
			.groupBy {
				new Key(sell_Date: it.sell_Date, transaction_id: it.transaction_id, receiverId : it.receiverId, senderResellerId: it.senderResellerId, profileId: it.profileId, resultCode: it.resultCode)
			}
			.collect {key, list ->
				[key: key, transactionCount: list.size()]
			}
			
			aggregation(transactions)
		}
		
		updateCursor(txTransactions)
		deleteOldTransactions()
		schedule(50, TimeUnit.MILLISECONDS)
	}
	
	private def aggregation(List aggregations)
	{
		log.info("Aggregated into ${aggregations.size()} rows.")
		if(aggregations)
		{
			def sql2 = "insert into dataaggregator.std_rollback_fail_reconciliation_aggregation (transactionType, ersReference, senderId, senderBalanceBefore, senderBalanceAfter, senderBalanceDifference, transactionDate, receiverId, receiverBalanceBefore, receiverBalanceAfter, receiverBalanceDifference, present_in_transaction_table) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql2, [
				setValues:
				{	ps, i ->
					def row = aggregations[i]
					
					def sql ="select * from accounts.transactions where importedReference in ('"+row.key.transaction_id+"')";
					def accountTransactionRow = accounts.query(sql, new ColumnMapRowMapper())
					BigDecimal sender_balance_before=0;
					BigDecimal sender_balance_after=0;
					BigDecimal receiver_balance_before=0;
					BigDecimal receiver_balance_after=0;
					BigDecimal senderBalanceDiff=0;
					BigDecimal receiverBalanceDiff=0;
					String present_in_transactions_table=0;
					
					for (int j = 0; j < accountTransactionRow.size(); j++) 
					{
						   present_in_transactions_table=1;
						   def accounts_row = accountTransactionRow.get(j)
						   if(accounts_row.accountId.toString().equals(row.key.senderResellerId.toString()))
						   {
							   sender_balance_before = sender_balance_before+new BigDecimal(accounts_row.balanceBefore.toString());
							   sender_balance_after = sender_balance_after+new BigDecimal(accounts_row.balanceAfter.toString());
							
						   }
						   else if(accounts_row.accountId.toString().equals(row.key.receiverId.toString()))
						   {
							   receiver_balance_before = receiver_balance_before+new BigDecimal(accounts_row.balanceBefore.toString());
							   receiver_balance_after = receiver_balance_after+new BigDecimal(accounts_row.balanceAfter.toString());
							
						   }
					}
					
					if(sender_balance_before==sender_balance_after)
					{
						senderBalanceDiff=sender_balance_before - sender_balance_after;
					}
					else if(sender_balance_before!=sender_balance_after)
					{
						senderBalanceDiff=sender_balance_before - sender_balance_after;
					}
					
					if(receiver_balance_before==receiver_balance_after)
					{
						receiverBalanceDiff=receiver_balance_after - receiver_balance_before;
					}
					else if(receiver_balance_before!=receiver_balance_after)
					{
						receiverBalanceDiff=receiver_balance_after - receiver_balance_before;
					}
					
					ps.setString(1, row.key.profileId.toString())
					ps.setString(2, row.key.transaction_id.toString())
					ps.setString(3, row.key.senderResellerId.toString())
					ps.setString(4, sender_balance_before.toString())
					ps.setString(5, sender_balance_after.toString())
					ps.setString(6, senderBalanceDiff.toString())
					ps.setTimestamp(7, toSqlTimestamp(row.key.sell_Date))
					ps.setString(8, row.key.receiverId.toString())
					ps.setString(9, receiver_balance_before.toString())
					ps.setString(10, receiver_balance_after.toString())
					ps.setString(11, receiverBalanceDiff.toString()) 
					ps.setString(12, present_in_transactions_table.toString())
					
				},
			getBatchSize:
			{ aggregations.size() }]
			 as BatchPreparedStatementSetter)		
			}
	}
	
	private def deleteOldTransactions()
	{
		def delete_sql ="delete from dataaggregator.std_rollback_fail_reconciliation_aggregation where present_in_transaction_table = '0' OR transactionDate <  (now() - INTERVAL 30 DAY)";
		def delete_sql_result = jdbcTemplate.update(delete_sql)
	}
	
}