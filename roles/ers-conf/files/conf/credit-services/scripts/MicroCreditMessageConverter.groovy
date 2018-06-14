package com.seamless.credit.queue.converters

import com.seamless.credit.requests.MonetaryTransactionRequest
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.support.converter.AbstractMessageConverter
import org.springframework.amqp.support.converter.MessageConversionException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

import static com.seamless.credit.util.CommonConstants.MESSAGE
import static com.seamless.credit.util.CommonConstants.TRANSACTION_REQ

/**
 * Message Converter for parsing messages from rabbit mq.
 * Created by danish on 11/16/17.
 */
@Service
class MicroCreditMessageConverter extends AbstractMessageConverter {

    static Logger log = LogManager.getLogger(MicroCreditMessageConverter.class);
    // To match TRANSFER_NOTIFICATION_QUEUE=${transaction.senderPrincipal.getResellerId()},${transaction.receiverPrincipal.resellerData.getResellerMSISDN()},${transaction.transactionAmount}
    // DIST1,245966010101,Amount[2000.00 FCFA]
    static final String PATTERN_STR = "(?<senderId>\\S+)\\s{0,},\\s{0,}(?<receiverMSISDN>\\S+)\\s{0,},s{0,}Amount\\[(?<transactionAmount>\\S+) (?<currency>\\S+)\\]\\s{0,}"
    static final Pattern PATTERN = Pattern.compile(PATTERN_STR);
    static final String SENDER_ID = "senderId"
    static final String RECEIVER_MSISDN = "receiverMSISDN"
    static final String TRANSACTION_AMOUNT = "transactionAmount"
    static final String TRANSACTION_CURRENCY = "currency"


    @Value('${credit-services.ers_request.channel}')
    String channel

    @Value('${credit-services.ers_request.clientid}')
    String clientId

    @Override
    Message createMessage(Object object, MessageProperties messageProperties) {
        return null
    }

    Object fromMessage(Message message) throws MessageConversionException {

        Map<String, Object> map
        MonetaryTransactionRequest monetaryTransactionRequest;
        String messageBody = new String(message.getBody())
        Matcher matcher = PATTERN.matcher(messageBody)

        try {

            log.debug("Message received: {}", messageBody)

            if (matcher.find()) {
                map = new ConcurrentHashMap()

                monetaryTransactionRequest = new MonetaryTransactionRequest(

                        id: matcher.group(RECEIVER_MSISDN).trim(),
                        amount: new BigDecimal(matcher.group(TRANSACTION_AMOUNT).trim()),
                        currency: matcher.group(TRANSACTION_CURRENCY).trim(),
                        channel: channel
                )
                map.put(MESSAGE, message)
                map.put(TRANSACTION_REQ, monetaryTransactionRequest)
            } else {
                log.info("Message does not match the pattern specified. Ignoring message: {}", messageBody)
            }

            return map
        }
        catch (Exception e) {

            log.error("An exception has occurred while conversion of message. Ignoring message: {}, with Exception: ", messageBody, e)
        }
    }
}
