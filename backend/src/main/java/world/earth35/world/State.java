package world.earth35.world;

import java.util.*;

/** Only this server-owned state is authoritative. Never serialize it to public clients. */
public class State {
    public long minute = 480;
    public long version = 0;
    public int geographyVersion;
    public int expansionVersion;
    public int transitVersion;
    public Map<String, Resident> agents = new LinkedHashMap<>();
    public Map<String, Building> buildings = new LinkedHashMap<>();
    public Map<String, Job> jobs = new LinkedHashMap<>();
    public Map<String, Long> accounts = new LinkedHashMap<>();
    public Map<String, Conversation> conversations = new LinkedHashMap<>();
    public Map<String, SocialPlan> socialPlans = new LinkedHashMap<>();
    public Map<String, PublicCall> publicCalls = new LinkedHashMap<>();
    public Map<String, Projects.Project> projects = new LinkedHashMap<>();
    public Map<String, Projects.Invitation> projectInvitations = new LinkedHashMap<>();
    public Map<String, Projects.Product> products = new LinkedHashMap<>();
    public Map<String, Ventures.Business> businesses = new LinkedHashMap<>();
    public Map<String, Construction.Site> construction = new LinkedHashMap<>();
    public Map<String, WorldObjects.PlacedObject> worldObjects = new LinkedHashMap<>();
    public Map<String, Loans.Loan> loans = new LinkedHashMap<>();
    public Economy.Cycle economy = new Economy.Cycle();

    public static class Resident {
        public String id, ownerId, name, description, personality, goals, home;
        public String location, occupation = "Unemployed", job = "", offer = "";
        public boolean fixture, paused, bedtimeRoutine;
        public double x, z, energy = 100, hunger = 10;
        public long money = 100000, arrears = 0, lastSeen = 0;
        public Map<String, Integer> inventory = new LinkedHashMap<>(Map.of("meal", 2));
        public Map<String, String> relationships = new LinkedHashMap<>();
        public Map<String, Long> encounterReviewed = new LinkedHashMap<>();
        public Double facingHeading;
        public long facingUntil;
        public Map<String, BookLoan> bookLoans = new LinkedHashMap<>();
        public Map<String, BookStudy> bookStudies = new LinkedHashMap<>();
        public Map<String, SubjectKnowledge> knowledge = new LinkedHashMap<>();
        public Map<String, Reasoning> reasoning = new LinkedHashMap<>();
        public Map<String, Creation> creations = new LinkedHashMap<>();
        public Set<String> interests = new LinkedHashSet<>();
        public Map<String, Projects.ResearchFinding> researchFindings = new LinkedHashMap<>();
        public long studyBoostUntil, weatherProtectionUntil;
        public Pending pending;
        public NightPlan nightPlan;
        public DailyRoutine routine;
        public long workDay = -1;
        public int workedMinutes;
        public int overtimeMinutes;
        public long leaveDay = -1;
        public WorkPermit workPermit;
        public double wetness;
        public boolean raincoatWorn;
        public long lastWakeMinute = -1;
        public int sleepDebtMinutes;
        public long lastMealMinute = -1;
        public double hungerAfterLastMeal;
        public long resumeSleepUntil = -1;
        public long hungerWakeUntil = -1;
        public boolean hungerWakeNeedsFood;
    }
    public static class DailyRoutine {
        public int wakeMinute = 420, sleepMinute = 1380;
        public String morningActivity = "breakfast";
    }
    public static class Conversation {
        public String a, b, lastSpeaker, closingSpeaker;
        public String initiator;
        public boolean engaged;
        public long lastMinute;
        public long turnSequence;
        public boolean closed;
        public List<Turn> turns = new ArrayList<>();
        public List<ConversationTravel> travel = new ArrayList<>();
    }
    public static class PublicCall {
        public String id, caller, listener, message, status = "pending", response;
        public long minute, expires;
        public Long responseMinute;
    }
    public static class BookLoan {
        public String book;
        public long borrowedMinute, dueMinute;
        public boolean overdueNotified;
    }
    public static class BookStudy {
        public long readingMinutes, lastReadMinute;
        public int studyMinutes;
    }
    public static class SubjectKnowledge {
        public long readingMinutes, lastStudiedMinute;
        public int studyMinutes;
        public int points, maximumPoints, intermediateConcepts, advancedConcepts, researchPoints;
        public Set<String> concepts = new LinkedHashSet<>();
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String getLevel() {return advancedConcepts>0?"advanced":intermediateConcepts>0?"intermediate":concepts.isEmpty()?"learning":concepts.size()<3?"introductory":"familiar";}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String getDepth() {return advancedConcepts>0?"advanced":intermediateConcepts>0?"intermediate":"introductory";}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public int getUnderstanding() {return maximumPoints<=0?0:Math.min(100,100*points/maximumPoints);}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public boolean getCanReason() {return points>=12&&concepts.size()>=4;}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public boolean getCanCreate() {return points>=28&&intermediateConcepts+advancedConcepts>=4;}
    }
    public record Reasoning(String id,String subject,String problem,String analysis,List<String> concepts,long minute) {}
    public record Creation(String id,String subject,String kind,String title,String content,String reasoningId,List<String> concepts,long minute) {}
    public record Turn(String speaker, String message, long minute, String location) {
        public Turn(String speaker,String message,long minute) {this(speaker,message,minute,null);}
    }
    public record ConversationTravel(String resident,String actionId,String origin,String destination,
                                     long departedMinute,Long arrivedMinute,String status) {}
    public static class SocialPlan {
        public String id, proposer, invited, destination, activity, status = "proposed";
        public long meetingMinute, expires;
    }
    public static class WorkPermit {
        public String job;
        public long from, until;
    }
    public static class NightPlan {
        public String reason;
        public long until;
        public NightPlan() {}
        public NightPlan(String reason,long until) {this.reason=reason;this.until=until;}
    }
    public static class Building {
        public String id, name, type, district;
        public String description = "";
        public boolean social, restAllowed;
        public Map<String, String> objects = new LinkedHashMap<>();
        public double x, z;
        public double outdoorRadius;
        public String plannedType;
        public Places.Entrance entrance;
        public int openMinute, closeMinute;
        public List<Integer> openDays = new ArrayList<>(List.of(0,1,2,3,4,5,6));
        public int capacity;
        public Map<String, Integer> stock = new LinkedHashMap<>();
        public Map<String, Long> prices = new LinkedHashMap<>();
        public Building() {}
        public Building(String id, String name, String type, String district, double x, double z, int capacity) {
            this.id=id; this.name=name; this.type=type; this.district=district; this.x=x; this.z=z; this.capacity=capacity;
        }
    }
    public static class Job {
        public String id, title, building;
        public String employerAccount = "";
        public int capacity;
        public long hourlyWage = 2000;
        public int startMinute = 540, endMinute = 1020;
        public List<Integer> workDays = new ArrayList<>(List.of(0,1,2,3,4));
        public int earlyAccessMinutes = 120, lateAccessMinutes = 120;
        public boolean outsideHoursWorkAllowed = true;
        public Job() {}
        public Job(String id, String title, String building, int capacity) {
            this.id=id; this.title=title; this.building=building; this.capacity=capacity;
        }
    }
    public static class Pending {
        public String id, type, target;
        public long until;
        public List<double[]> route = new ArrayList<>();
        public String vehicleId, boardingStop, waitingReason;
        public String finalDestination,boardingRoute,alightingStop;
        public long boardingMinute;
        public long boardingCompletedMinute=-1;
        public Integer queueIndex;
        public double[] waitingPoint;
        public List<Traffic.Pose> motion=new ArrayList<>();
        public String routeChoice,travelWeather;
        public long reviewAfter;
        public String socialWith, socialPlanId;
        public long socialUntil;
        public boolean frontDoorRoute;
        public boolean entering;
        public double[] exitDoor;
        public long startedAt;
        public long lastWorkedMinute = -1;
        public boolean mainSleep;
        // Legacy sleeps have no evidence of an inadequate bedtime meal.
        public double hungerWakeThreshold = 101;
        public int overtimeMinutes;
        public boolean readingOnSite;
        public int readingMinutes;
        public String projectMethod;
        public int workMinutes;
        public long reservedCost;
        public String objectId, objectKind, objectText, objectBusiness;
        public double objectX, objectZ;
        public Integer seatIndex;
    }
    public static State seed() {
        State s=prototypeSeed();OrganicCity.upgrade(s);CityExpansion.upgrade(s);Traffic.upgradeStops(s);Construction.initializeCitySites(s,new Engine.Effects());IslandLandscapes.upgrade(s);IslandLandscapes.completePhase3(s);return s;
    }
    public static State prototypeSeed() {
        State s = new State();
        // Road grid at multiples of 12; every building has an entrance on its east/west road.
        for (int i=0;i<50;i++) {
            String id = (i<40 ? "apt-" : "house-") + (i+101);
            s.buildings.put(id,new Building(id,(i<40?"Apartment ":"House ")+(i+101),i<40?"apartment":"house","Residential",-54+(i%5)*12,-54+(i/5)*12,1));
        }
        String[][] businesses={{"office","New Eden Works","office"},{"restaurant","The Green Table","restaurant"},{"coffee","Common Ground","coffee_shop"},{"grocery","Daily Goods","grocery"},{"studio","Form Studio","studio"},{"market","Corner Market","market"}};
        for(int i=0;i<businesses.length;i++) {
            String[] b=businesses[i];
            Building building=new Building(b[0],b[1],b[2],"Downtown",18+(i%3)*12,-30+(i/3)*24,20);
            if(!b[0].equals("office")&&!b[0].equals("studio")) {
                building.stock.put("meal",100); building.prices.put("meal",1200L);
                building.stock.put("coffee",100); building.prices.put("coffee",400L);
            }
            s.buildings.put(b[0],building);
            s.accounts.put(b[0],100000000L);
            s.jobs.put("job-"+b[0],new Job("job-"+b[0],switch(b[0]) {case "office"->"City analyst";case "studio"->"Designer";case "restaurant"->"Kitchen assistant";case "coffee"->"Barista";default->"Shop assistant";},b[0],10));
        }
        s.buildings.put("park",new Building("park","Central Park","park","Central Park",30,30,50));
        s.accounts.put("housing",0L);
        s=CityPlaces.populate(s);
        s=ScenicPlaces.populate(s);
        Library.configure(s);
        DailyRoutines.configureJobs(s);
        Traffic.populate(s);
        Places.configure(s);
        s.buildings.get("market").stock.put("raincoat",30);
        s.buildings.get("market").prices.put("raincoat",3500L);
        s.economy.lastDay=Math.floorDiv(s.minute,1440);
        Loans.initialize(s,new Engine.Effects());
        return s;
    }
}
