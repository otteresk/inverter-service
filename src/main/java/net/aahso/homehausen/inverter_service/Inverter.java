package net.aahso.homehausen.inverter_service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

import reactor.core.publisher.Mono;

@Component
public class Inverter {

	Logger logger = LoggerFactory.getLogger(this.getClass());
	private static final String API_URL = "/logdata/download";
	private String sessionID = "xxx";

	// injected
	private final WebClient webClient;
	private final String passwordFilename;

	public Inverter(WebClient wc, String pwFile) {
		this.webClient = wc;
		this.passwordFilename = pwFile;

		// get session ID of Inverter (get once, use multiple)
		this.sessionID = InverterAuthenticator.authenticate(wc, pwFile);

		if (this.sessionID == null) {
			logger.error("Error in Inverter constructor!!");
		} else {
			logger.info("Inverter successfully constructed!");
		}
	}

	// get the data from the inverter
	public Mono<String> getData(int dayDiff) {

		if (this.sessionID == null)
			return null;
		if (isSessionIdValid()==false)
			this.sessionID = InverterAuthenticator.authenticate(this.webClient, this.passwordFilename);

		DateTimeFormatter dtf = DateTimeFormatter.ofPattern("uuuu-MM-dd");
		LocalDateTime now = LocalDateTime.now();
		String datumsStr = dtf.format(now.minusDays(dayDiff));
		// String datumsStr = "2023-09-19";
		String inputJson = "{ \"begin\": \"" + datumsStr + "\", \"end\": \"" + datumsStr + "\"  }";
		// System.out.println("JSON in: "+inputJson);

		return this.webClient.post().uri(API_URL).header(HttpHeaders.AUTHORIZATION, "Session " + sessionID)
				.bodyValue(inputJson).exchangeToMono(this::handleResponse);

	}

	// error handling
	private Mono<String> handleResponse(ClientResponse response) {

		if (response.statusCode().is2xxSuccessful()) {
			return response.bodyToMono(String.class);
		} else if (response.statusCode().isSameCodeAs(HttpStatusCode.valueOf(401))) {
			// Handle client errors 401 unauthorized
			logger.warn("API Call returned 401");
			this.sessionID = "401";
			return response.bodyToMono(String.class); // what ever this is
		} else if (response.statusCode().is4xxClientError()) {
			// Handle client errors (e.g., 404 Not Found)
			return Mono.error(new RuntimeException("HTTP Error " + response.statusCode()));
		} else if (response.statusCode().is5xxServerError()) {
			// Handle server errors (e.g., 500 Internal Server Error)
			return Mono.error(new RuntimeException("Server error 5xx"));
		} else {
			// Handle other status codes as needed
			return Mono.error(new RuntimeException("Unexpected error"));
		}
	}

	// is the session ID valid (e.g. not short)
	public boolean isSessionIdValid() {
		if (this.sessionID==null) return false;
		return (this.sessionID.length() > 4);
	}
	
}
