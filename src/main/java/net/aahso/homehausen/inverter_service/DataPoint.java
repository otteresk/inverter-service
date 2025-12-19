package net.aahso.homehausen.inverter_service;

public class DataPoint {

	public String timeOfDay;
	public int powerUseFromPV;
	public int powerUseFromBattery;
	public int powerUseFromGrid;
	public int powerToBattery;
	public int powerToGrid;
	public int batterySoC;
	//public int powerFromBattery;
	//public int powerFromPV;
	//public int powerHome;
	
	public DataPoint (String time,
			int pPV,
			int pBat,
			int pGrid,
			int toBat,
			int toGrid,
			int batSoC
			) {
		this.timeOfDay = time;
		this.powerUseFromPV = pPV;
		this.powerUseFromBattery = pBat;
		this.powerUseFromGrid = pGrid;
		this.powerToBattery = toBat;
		this.powerToGrid = toGrid;
		this.batterySoC = batSoC;
	}
}
