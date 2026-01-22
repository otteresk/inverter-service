package net.aahso.homehausen.inverter_service;

public class DataPoint {

	private long timeStamp;
	private int fromPV;
	private int fromGrid;
	private int fromBat;
	private int useHome;
	private int levelBat;
	
	public DataPoint() {
	}
	
	public DataPoint (long timeStamp,
					int fromPV,
					int fromGrid,
					int fromBat,
					int useHome,
					int levelBat){
		this.timeStamp = timeStamp;
		this.fromPV = fromPV;
		this.fromGrid = fromGrid;
		this.fromBat = fromBat;
		this.useHome = useHome;
		this.levelBat = levelBat;
	}

	public long getTimeStamp() {
		return timeStamp;
	}
	
	public void setTimeStamp(long timeStamp) {
		this.timeStamp = timeStamp;
	}

	public int getFromPV() {
		return fromPV;
	}
	
	public void setFromPV(int fromPV) {
		this.fromPV = fromPV;
	}

	public int getFromGrid() {
		return fromGrid;
	}
	
	public void setFromGrid(int fromGrid) {
		this.fromGrid = fromGrid;
	}

	public int getFromBat() {
		return fromBat;
	}
	
	public void setFromBat(int fromBat) {
		this.fromBat = fromBat;
	}

	public int getUseHome() {
		return useHome;
	}
	
	public void setUseHome(int useHome) {
		this.useHome = useHome;
	}

	public int getLevelBat() {
		return levelBat;
	}
	
	public void setLevelBat(int levelBat) {
		this.levelBat = levelBat;
	}

}
