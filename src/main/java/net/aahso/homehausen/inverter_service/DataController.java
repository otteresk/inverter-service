package net.aahso.homehausen.inverter_service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
    // get data for today
    ////////////////////////////////////////////////////////////////////////////
    @GetMapping(path="/latest")
    @ResponseBody
    public DataPoint getLatestData() {

		// Request data from Inverter (takes some seconds)
		String response = this.inverter.getData().block();
		// try again if session ID is not valid (e.g. 401) -- this is dirty!!
		if (this.inverter.isSessionIdValid()==false) {
		}
		if (response != null) {
			System.out.println("Response JSON: "+response);
		}
		else return null;
                
        DataPoint a = new DataPoint(response, 0, 0, 0, 0, 0, 0);
        
        return a;
    }
	
}
