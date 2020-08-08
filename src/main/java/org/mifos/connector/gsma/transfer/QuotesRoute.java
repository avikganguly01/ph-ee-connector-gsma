package org.mifos.connector.gsma.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.gsma.account.dto.ErrorDTO;
import org.mifos.connector.gsma.auth.dto.AccessTokenStore;
import org.mifos.connector.gsma.transfer.dto.QuotesDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.mifos.connector.gsma.camel.config.CamelProperties.*;

@Component
public class QuotesRoute extends RouteBuilder {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccessTokenStore accessTokenStore;

    @Autowired
    private DataTransformer dataTransformer;

    @Value("${gsma.api.host}")
    private String BaseURL;

    @Value("${camel.host}")
    private String HostURL;

    @Autowired
    private QuoteResponseProcessor quoteResponseProcessor;

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public void configure() {

        from("direct:quote-route-base")
                .id("quote-route-base")
                .log(LoggingLevel.INFO, "Transfer route started")
                .to("direct:get-access-token")
                .process(exchange -> exchange.setProperty(ACCESS_TOKEN, accessTokenStore.getAccessToken()))
                .log(LoggingLevel.INFO, "Got access token, moving on to transform data")
                .process(exchange -> exchange.setProperty(QUOTE_REQUEST_BODY, dataTransformer
                        .getQuoteRequestBody(objectMapper.readValue(exchange
                                .getProperty(CHANNEL_REQUEST, String.class), TransactionChannelRequestDTO.class))))
                .log(LoggingLevel.INFO, "Moving on to API call")
                .to("direct:get-quote")
                .log(LoggingLevel.INFO, "Quote API response: ${body}")
                .setProperty(QUOTE_RESPONSE, simple("${body}"))
                .choice()
                .when(header("CamelHttpResponseCode").isEqualTo("201"))
                    .log(LoggingLevel.INFO, "Quote request successful")
                    .unmarshal().json(JsonLibrary.Jackson, QuotesDTO.class)
                    .setProperty(QUOTE_FAILED, constant(false))
                    .process(exchange -> {
                        exchange.setProperty(QUOTE_ID, exchange.getIn().getBody(QuotesDTO.class).getQuotes()[0].getQuoteId());
                        exchange.setProperty(QUOTE_REFERENCE, exchange.getIn().getBody(QuotesDTO.class).getQuotationReference());
                    })
                .otherwise()
                    .log(LoggingLevel.ERROR, "Quote request unsuccessful")
                    .unmarshal().json(JsonLibrary.Jackson, ErrorDTO.class)
                    .process(exchange -> {
                        exchange.setProperty(ERROR_INFORMATION, exchange.getIn().getBody(ErrorDTO.class).getErrorCode());
                    })
                    .setProperty(QUOTE_FAILED, constant(true))
                .endChoice()
                .process(quoteResponseProcessor);


        from("direct:get-quote")
                .id("get-quote")
                .removeHeader("*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("X-Date", simple(ZonedDateTime.now( ZoneOffset.UTC ).format( DateTimeFormatter.ISO_INSTANT )))
                .setHeader("Authorization", simple("Bearer ${exchangeProperty."+ACCESS_TOKEN+"}"))
                .setHeader("Content-Type", constant("application/json"))
                .setBody(exchange -> exchange.getProperty(QUOTE_REQUEST_BODY)) // To be changed to QuoteDTO Body
                .marshal().json(JsonLibrary.Jackson)
                .log(LoggingLevel.INFO, "Quote Request Body: ${body}")
                .toD(BaseURL + "/quotations" + "?bridgeEndpoint=true&throwExceptionOnFailure=false");


    }

}