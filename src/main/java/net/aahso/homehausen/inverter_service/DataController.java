package net.aahso.homehausen.inverter_service;

import java.util.List;
import java.util.ArrayList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.NumberFormatException;

import java.time.ZoneId;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseBody;

@RestController // This means that this class is a REST Controller
@RequestMapping(path="/data") // This means URL's start with /data (after Application path)
public class DataController {

	private final Inverter inverter;

	private Logger logger = LoggerFactory.getLogger(this.getClass());

	private String cachedLogDataString = "";
	private   long cachedLastEntryTime = -1;
	
	public DataController(Inverter inv) {
        this.inverter = inv;
        logger.info("DataController constructed!");
	}
	
    ////////////////////////////////////////////////////////////////////////////
    // get data for today
    ////////////////////////////////////////////////////////////////////////////
    @GetMapping(path="/today")
    @ResponseBody
    public List<DataPoint> getSurveySummary(@RequestParam String yesterday) {

    	//logger.info("yesterday parameter: " + yesterday);
    	
    	int dayDiff=0;
    	if (yesterday.equals("1")) {
    		dayDiff=1;
    	}
    	else if (!yesterday.equals("0")) {
    		logger.warn("Unknown value for \"yesterday\": "+yesterday);
    	}
    	
    	//final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss");
    	final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm");
        final ZoneId localZoneId = ZoneId.of("Europe/Berlin");
        final Date currentDate = new Date();
        final long nowTimeStamp = currentDate.getTime()/1000;

        List<DataPoint> dataList = new ArrayList<DataPoint>();
        
        String logDataString="";
        long cacheAge = nowTimeStamp - cachedLastEntryTime;
        if ( cacheAge < 300 && dayDiff == 0) {
        	// use String from Cache
        	logger.info("Cache Age: " + cacheAge + " seconds -- using it");
        	logDataString = cachedLogDataString;
        }
        else {
        	if (dayDiff==0) {
        		logger.info("Cache Age: " + cacheAge + " seconds -- loading data from inverter");
        	}
        	else {
        		logger.info("Yesterday -- loading data from inverter");
        	}
            // Request data from Inverter (takes some seconds)
        	String response = this.inverter.getData(dayDiff).block();
        	// try again if session ID is not valid (e.g. 401) -- this is dirty!!
        	if (this.inverter.isSessionIdValid()==false) response = this.inverter.getData(dayDiff).block(); 
        	if (response != null) {
        		logDataString = response;
        		if (dayDiff==0) cachedLogDataString = logDataString;
        	}
        	else return dataList; // empty
        }
        
        long timestamp = -42;
        try (BufferedReader reader = new BufferedReader(new StringReader(logDataString))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split("\t");
                //System.out.println("Elements in line: " + values.length);
                // only real data lines - no headers
                if (values.length > 47 && !values[0].equals("Zeit")) {
                    // only data lines having detailed inverter data
                	if (values[1].length() > 0) {
                		try {
                			timestamp = Integer.parseInt(values[0]);
                			Instant instant = Instant.ofEpochSecond(timestamp);
                	        LocalDateTime localDateTime = LocalDateTime.ofInstant(instant, localZoneId);
                	        DataPoint dp =  new DataPoint(dtf.format(localDateTime),
                	        		//Integer.parseInt(values[3]) + Integer.parseInt(values[8]) // total power from PV
                	        		Integer.parseInt(values[45]), // home used power from PV
                	        		Integer.parseInt(values[44]), // home used power from battery
                	        		Math.max(Integer.parseInt(values[46]),0), // home used power from grid
                	        		Math.max(-Integer.parseInt(values[13]),0), // power to battery
                	        		Math.max(0,Integer.parseInt(values[18])+Integer.parseInt(values[22])+Integer.parseInt(values[26])-
                	        			Integer.parseInt(values[38])-Integer.parseInt(values[46])), // power to grid
                	        		Integer.parseInt(values[47])  // Battery SoC
                	        		);
                			dataList.add(dp);
                        
                		} catch (NumberFormatException nfe) {
                			logger.error("A String could not be converted to Integer in line: "+line);
                			// do nothing
                		}
                	}
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        if (dayDiff==0) cachedLastEntryTime = timestamp;
        
        return dataList;
    }
	
}
