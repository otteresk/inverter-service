package net.aahso.homehausen.inverter_service;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;

import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

import reactor.core.publisher.Mono;

@Component
public class Inverter {

	private static final String API_URL = "/processdata";
	private static final String NOSESSION = "xx";
	private String sessionID = NOSESSION;

	@Autowired
	private Environment env;

	// injected
	private final WebClient aahsoWebClient;
	private final WebClient inverterWebClient;
	private final String passwordFilename;
    private final TaskExecutor taskExecutor;

    private volatile boolean running = true;
    private volatile Thread workerThread;
	private ObjectMapper objectMapper = new ObjectMapper();
	private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final ArrayNode APIrequest = createRequest();

	private LinkedList<DataPoint> listDP = new LinkedList<DataPoint>();

	// Constructor for Inverter
	public Inverter(@Qualifier("inverterWebClient") WebClient wc, String pwFile,
					@Qualifier("aahsoWebClient") WebClient awc, TaskExecutor taskExecutor) {
		this.inverterWebClient = wc;
		this.passwordFilename = pwFile;
		this.aahsoWebClient = awc;
        this.taskExecutor = taskExecutor;

		// get session ID of Inverter (get once, use multiple)
		this.sessionID = InverterAuthenticator.authenticate(wc, pwFile);

		if (this.sessionID == null) {
			logger.error("Error: no session ID in Inverter constructor");
		} else {
			logger.info("Inverter successfully constructed!");
		}

	}

    @EventListener(ApplicationReadyEvent.class)
    public void runDataPump(ApplicationReadyEvent ev) {
        taskExecutor.execute(() -> {
            workerThread = Thread.currentThread();
		    int loopCount = 0;
			String responseJson = "";
            try {
                while (running && loopCount > -3) {
                    try {

						// start with some sleep
                        Thread.sleep(3000);

                        loopCount++;
                        System.out.println("Loop: " + loopCount);
                        
						responseJson = fetchDataFromInverter();
						//System.out.println("Response: " + responseJson);
						if (responseJson == null) {
							System.out.println("No response from Inverter API");
							continue;
						}

						// create data point from inverter json
						DataPoint dp = extractDataFromJson(responseJson);
						listDP.add(dp);

						// write data to file
						saveDataPointToFile(dp);

						// remove old data points (older than 10 minutes)
						if (loopCount%100 == 0)
							listDP.removeIf((DataPoint l) -> l.getTimeStamp() < Instant.now().getEpochSecond() - 3600);
						System.out.println("DataPoints: " + listDP.size());

						// send data to aahso.net
						sendDataToAahso(dp);

                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        running = false;
                    } catch (Exception e) {
                        // Log error but keep running
                        System.err.println("Error in Data Pump: " + e.getMessage());
                    }
                }
            } finally {
                workerThread = null;
            }
        });
    }

	// get data point from JSON response
	private DataPoint extractDataFromJson(String respJson)
		throws JsonProcessingException, JsonParseException {

		JsonNode jsonNode = objectMapper.readTree(respJson);
		//System.out.println("Parsed JSON size: " + jsonNode.size());

		int pv1 = jsonNode.get(0).get("processdata").get(0).get("value").asInt();
		int pv2 = jsonNode.get(1).get("processdata").get(0).get("value").asInt();
		int pvTotal = pv1 + pv2;
		//System.out.println("Parsed JSON PV total: " + pvTotal);

		int fromGrid = jsonNode.get(3).get("processdata").get(0).get("value").asInt();
		//System.out.println("Parsed JSON from Grid: " + fromGrid);

		int fromBat = jsonNode.get(2).get("processdata").get(0).get("value").asInt();
		//System.out.println("Parsed JSON from Battery: " + fromBat);

		int homeUse = jsonNode.get(3).get("processdata").get(4).get("value").asInt();
		//System.out.println("Parsed JSON Home Use: " + homeUse);

		int soc = jsonNode.get(2).get("processdata").get(1).get("value").asInt();
		//System.out.println("Parsed JSON Battery SoC: " + soc);

		Instant instant = Instant.now();
		long timeStampSeconds = instant.getEpochSecond();

		return new DataPoint(
			timeStampSeconds,
			pvTotal,
			fromGrid,
			fromBat,
			homeUse,
			soc
		);
	}

	// get the data from the inverter
	private String fetchDataFromInverter() {

		if (this.sessionID==null || this.sessionID.equals(NOSESSION)) {
			this.sessionID = InverterAuthenticator.authenticate(this.inverterWebClient, this.passwordFilename);
		}
		if (this.sessionID == null)
			return null;

        // prepate request JSON
        ArrayNode procDataArray = APIrequest;
        String inputJson = procDataArray.toString();
		//System.out.println("JSON in: "+inputJson);

		return this.inverterWebClient.post().uri(API_URL).header(HttpHeaders.AUTHORIZATION, "Session " + sessionID)
				.bodyValue(inputJson).exchangeToMono(this::handleResponse).block();

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

	// return latest (stored) data point
	public DataPoint getLatestData() {
		return listDP.peekLast();
	}

	// return latest (stored) data points not older than x seconds
	public LinkedList<DataPoint> getLatestDataPoints(int seconds) {
		long threshold = Instant.now().getEpochSecond() - seconds;
		LinkedList<DataPoint> recentDPs = new LinkedList<DataPoint>();
		Iterator<DataPoint> iterator = listDP.descendingIterator();
	    while (iterator.hasNext()) {
			DataPoint dp = iterator.next();
			if (dp.getTimeStamp() < threshold) break;
			System.out.println(dp.getTimeStamp());
       		recentDPs.add(dp);
		}
		return recentDPs;
	}
	
	// save data point to file
	private void saveDataPointToFile(DataPoint dp) {
		String saveFilename = env.getProperty("app.savefilename");

		try (FileWriter myWriter = new FileWriter(saveFilename, true)) {
			myWriter.write(objectMapper.writeValueAsString(dp)+"\n");
		} catch (IOException e) {
			System.out.println("Error writing to file.");
			e.printStackTrace();
		}
	}

	// send latest data to aahso.net
	public void sendDataToAahso(DataPoint dp) {

    	ObjectNode JsonData = objectMapper.createObjectNode();

    	JsonData.put("Time", LocalDateTime.ofInstant(Instant.ofEpochSecond(dp.getTimeStamp()), ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    	JsonData.put("PV", dp.getFromPV());
    	JsonData.put("FromGrid", dp.getFromGrid());
    	JsonData.put("FromBat", dp.getFromBat());
    	JsonData.put("Home_Total", dp.getUseHome());
    	JsonData.put("Battery_Level", dp.getLevelBat());
        
		this.aahsoWebClient.post().uri("/send_data.php").header(HttpHeaders.AUTHORIZATION, "Session " + sessionID)
				.bodyValue(JsonData.toString()).exchangeToMono(this::handleResponse).block();
	}

    // create request JSON
    private ArrayNode createRequest() {

        ArrayNode procDataArray = objectMapper.createArrayNode();
        ObjectNode moduleObject;
        ArrayNode processdataNames;

        // PV array 1
        moduleObject = objectMapper.createObjectNode();
        moduleObject.put("moduleid", "devices:local:pv1");
        processdataNames = objectMapper.createArrayNode();
        processdataNames.add("P");
        moduleObject.set("processdataids", processdataNames);
        procDataArray.add(moduleObject);

        // PV array 2
        moduleObject = objectMapper.createObjectNode();
        moduleObject.put("moduleid", "devices:local:pv2");
        processdataNames = objectMapper.createArrayNode();
        processdataNames.add("P");
        moduleObject.set("processdataids", processdataNames);
        procDataArray.add(moduleObject);

        // Battery
        moduleObject = objectMapper.createObjectNode();
        moduleObject.put("moduleid", "devices:local:battery");
        processdataNames = objectMapper.createArrayNode();
        processdataNames.add("SoC");
        processdataNames.add("P");
        moduleObject.set("processdataids", processdataNames);
        procDataArray.add(moduleObject);

        // Power Flow
        moduleObject = objectMapper.createObjectNode();
        moduleObject.put("moduleid", "devices:local");
        processdataNames = objectMapper.createArrayNode();
        processdataNames.add("Grid_P");
        processdataNames.add("Home_P");
        processdataNames.add("HomeGrid_P");
        processdataNames.add("HomeOwn_P");
        processdataNames.add("HomeBat_P");
        processdataNames.add("PV2Bat_P");
        moduleObject.set("processdataids", processdataNames);
        procDataArray.add(moduleObject);

        return procDataArray;
    }


	// clean shut down
    @PreDestroy
    private void stopThread() {
        logger.info("Shutdown requested: stopping Inverter data pump");
        running = false;
        Thread t = workerThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }


}
