package world.earth35.protocol;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.util.*;

public class Contracts {
    public enum ActionType { MOVE, RUN, JUMP, LOOK, TALK, CALL_OUT, ACKNOWLEDGE, DECLINE_CALL, BUY, SELL, EAT, WORK, REQUEST_OVERTIME, TAKE_LEAVE, CANCEL_LEAVE, REST, SLEEP, WEAR, REMOVE_CLOTHING, APPLY_JOB, ACCEPT_JOB, QUIT_JOB, USE_OBJECT, TRANSFER, BOARD_BUS, WAIT_BUS, TRAVEL_BUS, CONTINUE_TRAVEL, WAIT_RAIN, PAUSE_TRAVEL, END_CHAT, RELATE, PROPOSE_PLAN, ACCEPT_PLAN, DECLINE_PLAN, CANCEL_PLAN, JOIN_PLAN, READ_BOOK, BORROW_BOOK, RETURN_BOOK, REASON, CREATE_WORK, DECLARE_INTEREST, START_PROJECT, INVITE_PROJECT, ACCEPT_PROJECT, DECLINE_PROJECT, RESEARCH_PROJECT, BUILD_PROJECT, USE_PRODUCT, ENDORSE_BUSINESS, LAUNCH_BUSINESS, START_BUSINESS, FUND_BUSINESS, SET_PRODUCT_PRICE, PRODUCE_PRODUCT, WITHDRAW_PROFIT, START_CONSTRUCTION, BUILD_CONSTRUCTION, REPAY_LOAN, PLACE_OBJECT, MOVE_OBJECT }
    public record Credentials(@Email @NotBlank @Size(max=254) String email, @NotBlank @Size(min=12,max=128) String password) {}
    public record Registration(@NotBlank @Size(max=40) String name, @NotNull @Size(max=300) String description,
        @NotNull @Size(max=2000) String personality, @NotNull @Size(max=2000) String goals,
        @Pattern(regexp="apartment|house|any") String homeType, boolean fixture, @Valid HostedSetup hosted) {}
    public record HostedSetup(@NotBlank @Size(max=40) String provider,
        @NotBlank @Size(max=120) String model, @NotBlank @Size(max=512) String apiKey) {}
    public record Command(@NotBlank @Size(max=80) String requestId, @NotNull ActionType action,
        @Size(max=80) String target, @Size(max=8000) String message, @Size(max=80) String item,
        @Min(0) @Max(100) Integer quantity, @Min(1) @Max(1000000) Long amount,
        @Min(3) @Max(5) @io.swagger.v3.oas.annotations.media.Schema(description="Explicit three- or five-year financing for a business or construction shortfall") Integer loanYears,
        @Pattern(regexp="paid|self") @io.swagger.v3.oas.annotations.media.Schema(allowableValues={"paid","self"},description="Paid workers or unpaid sponsor labor for START_CONSTRUCTION") String laborMode) {
        public Command(String requestId,ActionType action,String target,String message,String item,Integer quantity,Long amount) {
            this(requestId,action,target,message,item,quantity,amount,null,null);
        }
        @AssertTrue @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isLoanTermValid() {return loanYears==null||loanYears==3||loanYears==5;}
    }
    public record Receipt(String id, String status, String result, long worldMinute) {}
    public record Pause(boolean paused) {}
    public record NightPlanInput(@NotNull @Size(max=300) String reason,@Min(0) long until) {}
    public record NightRoutineResult(Receipt action) {}
    public record NightRoutineRequest(@NotBlank @Size(max=80) String requestId) {}
    public record Usage(@NotBlank @Size(max=80) String requestId, @Min(0) @Max(10000000) long tokens) {}
    public record MemoryInput(@NotBlank @Size(max=80) String requestId, @Pattern(regexp="episodic|semantic") @NotNull String kind,
        @NotBlank @Size(max=4000) String content, @NotNull @Size(max=20) List<@NotBlank @Size(max=80) String> entities,
        @NotNull @Size(max=20) List<@Positive Long> sourceEvents, @Size(min=384,max=384) List<@NotNull Double> embedding,
        @Size(max=120) String embeddingModel) {}
    public record Recall(@NotNull @Size(max=300) String query, @Min(5) @Max(20) int limit,
        @Size(min=384,max=384) List<@NotNull Double> embedding, @Size(max=120) String embeddingModel,
        @Size(max=20) List<@NotBlank @Size(max=80) String> entities) {
        public Recall {entities=entities==null?List.of():List.copyOf(entities);}
        public Recall(String query,int limit,List<Double> embedding,String model){this(query,limit,embedding,model,List.of());}
    }
    public record Relationship(@NotBlank @Size(max=80) String target, @NotBlank @Size(max=100) String label) {}
    public record PublicAgent(String id, String name, String description, String home, String occupation,
        String location, double x, double z, String activity, String status, boolean fixture,boolean raincoatWorn,Integer seatIndex,
        String vehicleId,List<world.earth35.world.Traffic.Pose> motion,Double facingHeading,long facingUntil) {
        public PublicAgent(String id,String name,String description,String home,String occupation,String location,double x,double z,String activity,String status,boolean fixture,boolean raincoatWorn,Integer seatIndex,String vehicleId,List<world.earth35.world.Traffic.Pose> motion) {
            this(id,name,description,home,occupation,location,x,z,activity,status,fixture,raincoatWorn,seatIndex,vehicleId,motion,null,0);
        }
        public PublicAgent(String id,String name,String description,String home,String occupation,String location,double x,double z,String activity,String status,boolean fixture,boolean raincoatWorn) {
            this(id,name,description,home,occupation,location,x,z,activity,status,fixture,raincoatWorn,null,null,List.of());
        }
        public PublicAgent(String id,String name,String description,String home,String occupation,String location,double x,double z,String activity,String status,boolean fixture,boolean raincoatWorn,Integer seatIndex) {
            this(id,name,description,home,occupation,location,x,z,activity,status,fixture,raincoatWorn,seatIndex,null,List.of());
        }
    }
    public record Event(long id, long worldMinute, String kind, String actorId, String targetId, String message) {}
    public record World(long minute, long version, String weather, int temperature, long tickMs, List<StateBuilding> buildings, List<PublicAgent> agents, List<Event> events, world.earth35.world.Traffic.Network transport,world.earth35.world.Economy.Summary economy,world.earth35.world.CityLayout geography,world.earth35.world.Projects.PublicView innovations,world.earth35.world.Construction.View construction,List<world.earth35.world.WorldObjects.ObjectView> objects) {
        public World(long minute,long version,String weather,int temperature,long tickMs,List<StateBuilding> buildings,List<PublicAgent> agents,List<Event> events,world.earth35.world.Traffic.Network transport,world.earth35.world.Economy.Summary economy,world.earth35.world.CityLayout geography,world.earth35.world.Projects.PublicView innovations) {
            this(minute,version,weather,temperature,tickMs,buildings,agents,events,transport,economy,geography,innovations,null,List.of());
        }
        public World(long minute,long version,String weather,int temperature,long tickMs,List<StateBuilding> buildings,List<PublicAgent> agents,List<Event> events,world.earth35.world.Traffic.Network transport,world.earth35.world.Economy.Summary economy,world.earth35.world.CityLayout geography) {
            this(minute,version,weather,temperature,tickMs,buildings,agents,events,transport,economy,geography,null);
        }
    }
    public record RoutineInput(@Min(0) @Max(1439) int wakeMinute,@Min(0) @Max(1439) int sleepMinute,
        @NotNull @Pattern(regexp="breakfast|jogging|reading|meditation") String morningActivity) {}
    public record StateBuilding(String id,String name,String type,String district,double x,double z,int capacity,String description,boolean social,boolean restAllowed,Map<String,String> objects,world.earth35.world.Places.Entrance entrance,world.earth35.world.Places.Hours hours,double outdoorRadius,String plannedType) {
        public StateBuilding(String id,String name,String type,String district,double x,double z,int capacity,String description,boolean social,boolean restAllowed,Map<String,String> objects,world.earth35.world.Places.Entrance entrance,world.earth35.world.Places.Hours hours) {
            this(id,name,type,district,x,z,capacity,description,social,restAllowed,objects,entrance,hours,0,null);
        }
    }
}

