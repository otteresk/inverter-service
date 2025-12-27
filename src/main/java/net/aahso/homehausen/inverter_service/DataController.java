package net.aahso.homehausen.inverter_service;

import java.util.LinkedList;

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

	public DataController(Inverter inv) {
        this.inverter = inv;
        logger.info("DataController constructed!");
	}
	
    ////////////////////////////////////////////////////////////////////////////
    // get latest data from inverter 
    ////////////////////////////////////////////////////////////////////////////
    @GetMapping(path="/latest")
    @ResponseBody
    public DataPoint getLatestData() {

		// get latest data from Inverter class
		DataPoint response = this.inverter.getLatestData();
		if (response != null) {
			System.out.println("Response JSON: "+response);
		}
		else return null;
        
        return response;
    }
	
    @GetMapping(path="/latestAverage")
    @ResponseBody
    public DataPoint getLatestAverage( @RequestParam(name="seconds", defaultValue="30") int seconds) {

		if (seconds < 4 || seconds > 120 ) seconds = 30;

		// get latest data from Inverter class
		LinkedList<DataPoint> recentDPs = this.inverter.getLatestDataPoints(seconds);

		if (recentDPs.size() < 1) {
			System.out.println("getLatestDataPoints: No data points");
			return null;
		}
	
		long timeStamp = 0;
		int fromPV = 0;
		int fromGrid = 0;
		int fromBat = 0;
		int useHome = 0;
		int levelBat = 0;

		int count = 0;
		for (DataPoint dp : recentDPs) {
			count++;
			timeStamp+=dp.getTimeStamp();
			fromPV+=dp.getFromPV();
			fromGrid+=dp.getFromGrid();
			fromBat+=dp.getFromBat();
			useHome+=dp.getUseHome();
			levelBat+=dp.getLevelBat();
		}
		
		timeStamp /= count;
		fromPV /= count;
		fromGrid /= count;
		fromBat /= count;
		useHome /= count;
		levelBat /= count;

        return new DataPoint(timeStamp, fromPV, fromGrid, fromBat, useHome, levelBat);
    }




}
