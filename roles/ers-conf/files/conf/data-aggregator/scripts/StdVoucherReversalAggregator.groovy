package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import java.text.MessageFormat

/**
 * Created by ambujasahu on 24/04/17.
 */
@Log4j
@DynamicMixin
public class StdVoucherReversalAggregator extends AbstractAggregator {

    static final def TABLE = "std_voucher_reversal_aggregation"

    @Value('${StdVoucherReversalAggregator.reverseInProfiles:REVERSE_PURCHASE}')
    String transferInProfiles

    @Value('${StdVoucherReversalAggregator.batch:1000}')
    int limit

    @Value('${StdVoucherReversalAggregator.currMul:100}')
    int currencyMultiplier

    @Transactional
    @Scheduled(cron = '${StdVoucherReversalAggregator.cron:0 0 0 * * ?}')
    @Override
    public void aggregate() {

        log.debug("Running Aggregate:: StdVoucherReversalAggregator")
        def allowedProfiles = [transferInProfiles]

        def transactions = getTransactions(limit)
        if (transactions) {

            log.info(MessageFormat.format("Found {0} number of transactions for allowed profiles {1}.",
                    transactions.size(), allowedProfiles))

            List resellerTransactions = findAllowedProfiles(transactions, allowedProfiles)
                    .findAll(successfulTransactions)
                    .collect(transactionInformation)
                    .findAll()

            updateAggregation(resellerTransactions)
            updateCursor(transactions)
            schedule()
        } else {
            log.debug(MessageFormat.format("No transactions found for allowed profiles {0}", allowedProfiles))
        }
    }

    private def transactionInformation = {

        log.info("Processing voucher reversal transaction: ${it}")
        def tr = parser.parse(it.getJSON()).getAsJsonObject()
        log.debug("TransactionData --- "+tr)
		def state = tr.get("state")?.getAsString()
		if (state == "Reversed")
		{
						def ersReference = tr.get("originalTransactionErsReference")?.getAsString()
                        def transactionDate = it.getEndTime()
                        def resellerid = tr.get("principal")?.getAsJsonObject()?.get("resellerId")?.getAsString()
                        def posId = tr.get("principal")?.getAsJsonObject()?.get("resellerMSISDN")?.getAsString()
                        def propsMap = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()
                        def serial = propsMap.get("voucherSerial")?.getAsString()
                        def datestring = ersReference.substring(0,13)
                        def dateTime = new Date().parse("yyyyMMddHHmmss",datestring)
                        def serialDenomination =new BigDecimal(propsMap.get("VOUCHER_DENOM")?.getAsString()).divide(new BigDecimal(currencyMultiplier))
                        def reason = propsMap.get("comment")?.getAsString()
                        def blockedBy =  propsMap.get("REQUEST_REVERSAL_BY")?.getAsString()

						def reimbursedValue =""
                        tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect {
                                log.debug("process transaction row:" + it)
                                if (it.get("principalType")?.getAsString()  == "Sender"){
                                        reimbursedValue = getBigDecimalFieldFromAnyField(it,"value","amount")
                                }
                        }

			[
			dateTime			:dateTime,
			posId				:posId,
			resellerid			:resellerid,
			serial				:serial,
			serialDenomination	:serialDenomination,
		    transactionDate     :transactionDate,
		    reason				:reason,
		    blockedBy			:blockedBy,
		    reimbursedValue 	:reimbursedValue,
		    ersReference		:ersReference
		    ]
    }
    }

    private updateAggregation(List transactions){

		def insertSql = "INSERT INTO ${TABLE} (datetime, pos_id, reseller_id, serial, serial_denomination, blocked_date, reason, blocked_by, reimbursement_date, reimbursed_value, ers_reference) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

 		log.debug("insertSql in updateAggregation: "+insertSql)
        if(transactions && ! transactions.isEmpty()){

            log.info("Insertion data size : "+transactions.size())
            int index = 0

            jdbcTemplate.batchUpdate(insertSql, [
                    setValues   : { ps, i ->
                        index = 0;
                        def t = transactions.get(i);
                        ps.setTimestamp(++index, toSqlTimestamp(t.dateTime))
                        ps.setString(++index, t.posId)
                        ps.setString(++index, t.resellerid)
                        ps.setString(++index, t.serial)
                        ps.setBigDecimal(++index,t.serialDenomination)
                        ps.setTimestamp(++index,toSqlTimestamp(t.transactionDate))
                        ps.setString(++index, t.reason)
                        ps.setString(++index, t.blockedBy)
                        ps.setTimestamp(++index, toSqlTimestamp(t.transactionDate))
                        ps.setBigDecimal(++index, t.reimbursedValue)
                        ps.setString(++index, t.ersReference)

                    },
                    getBatchSize: { transactions.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
}