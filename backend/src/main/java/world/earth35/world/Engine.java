package world.earth35.world;

import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;
import java.util.*;

/** Deterministic mechanics: no model calls, network, wall clock, or database access. */
public class Engine {
    public record Fact(String kind,String actor,String target,String message) {}
    public record Payment(String source,String destination,long amount,String reason) {}
    public record Completion(String id,String status,String result) {}
    public record Started(String resident,Command command,String id,String result) {}
    public static class Effects {
        public List<Fact> facts=new ArrayList<>();
        public List<Payment> payments=new ArrayList<>();
        public List<Completion> completions=new ArrayList<>();
        public List<Started> starts=new ArrayList<>();
    }
    public static class Invalid extends RuntimeException { public Invalid(String message) { super(message); } }
    static void require(boolean condition,String message) { if(!condition) throw new Invalid(message); }
    static boolean present(String value) { return value!=null&&!value.isBlank(); }
    static void at(Resident a,String location) { require(location.equals(a.location),"Move to " + location + " first"); }
    static void together(Resident a,Resident b) { require(b!=null&&!a.id.equals(b.id)&&distance(a.x,a.z,b.x,b.z)<=10,"Target must be another nearby resident"); }
    static double distance(double x,double z,double bx,double bz) { return Math.hypot(x-bx,z-bz); }
    public static String activity(Resident a) { return a.pending==null?"Idle":a.pending.socialWith!=null?"CHAT":a.pending.type; }
    public static boolean busy(Resident a) {
        return a!=null&&a.pending!=null&&!a.pending.activityPaused&&List.of("SLEEP","WORK","BOARD_BUS","RIDE_BUS","READ_BOOK","RESEARCH_PROJECT","BUILD_PROJECT","PRODUCE_PRODUCT","BUILD_CONSTRUCTION","PLACE_OBJECT","MOVE_OBJECT","DO_ACTIVITY").contains(a.pending.type);
    }
    /** Sitting and resting do not prevent a voluntary conversation or invitation. */
    public static boolean restingConversation(Resident a,ActionType action) {
        return a.pending!=null&&(a.pending.activityPaused||List.of("REST","PLAN_WAIT").contains(a.pending.type))&&List.of(ActionType.TALK,ActionType.END_CHAT,
            ActionType.RELATE,ActionType.PROPOSE_PLAN,ActionType.ACCEPT_PLAN,ActionType.DECLINE_PLAN,ActionType.CANCEL_PLAN).contains(action);
    }
    public static String weather(long minute) {
        // Mix before taking the remainder: the old multiplier and seed were both
        // divisible by five, which left the entire world permanently rainy.
        long front=(Math.floorDiv(minute,360)*1103515245L+12345L) >>> 16;
        return switch((int)(front%5)) {case 0->"Rain";case 1->"Overcast";default->"Clear";};
    }
    public static int temperature(long minute) {
        // Warmest at 15:00, coolest at 03:00; weather cools the same shared city.
        double daily=22+4*Math.cos((Math.floorMod(minute,1440)-900)*Math.PI/720);
        int cooling=switch(weather(minute)) {case "Rain"->4;case "Overcast"->2;default->0;};
        return (int)Math.round(daily)-cooling;
    }

    public static String apply(State s,Resident a,Command c,String actionId,Effects e) {
        String result=applyCommand(s,a,c,actionId,e);
        if(a.pending!=null&&actionId.equals(a.pending.id)) {
            if(a.pending.startedAt==0)a.pending.startedAt=s.minute;
            if(ActionPerformance.controllable(a))a.pending.reviewAfter=s.minute+15;
        }
        ActionPerformance.accepted(s,a,c,actionId);
        PlanExecution.attachAction(s,a,c,actionId);
        return result;
    }
    private static String applyCommand(State s,Resident a,Command c,String actionId,Effects e) {
        require(!a.paused,"Agent participation is paused");
        if(ActionPerformance.control(c.action())) {
            String result=ActionPerformance.control(s,a,c,e);
            if(a.pending!=null)a.pending.reviewAfter=s.minute+15;
            return result;
        }
        require(a.pending==null||a.pending.activityPaused&&ActionPerformance.selfCare(c.action())||restingConversation(a,c.action())||c.action()==ActionType.CANCEL_PLAN&&Objects.equals(c.target(),a.pending.socialPlanId)||ActionPerformance.awake(a)&&List.of(ActionType.LOOK,ActionType.CALL_OUT,ActionType.ACKNOWLEDGE,ActionType.DECLINE_CALL).contains(c.action())||c.action()==ActionType.QUIT_JOB&&("WORK".equals(a.pending.type)||Travel.moving(a))||Travel.moving(a)&&List.of(ActionType.MOVE,ActionType.RUN,ActionType.TRAVEL_BUS,ActionType.CONTINUE_TRAVEL,
            ActionType.PAUSE_TRAVEL,ActionType.END_CHAT,ActionType.TALK,ActionType.LOOK,ActionType.RELATE,
            ActionType.PROPOSE_PLAN,ActionType.ACCEPT_PLAN,ActionType.DECLINE_PLAN,ActionType.CANCEL_PLAN,ActionType.JOIN_PLAN,ActionType.WEAR,ActionType.REMOVE_CLOTHING).contains(c.action()),"An action is already in progress");
        String target=c.target()==null?"":c.target();
        require(c.message()==null||c.message().length()<=(c.action()==ActionType.REASON||c.action()==ActionType.CREATE_WORK?8000:1000),"Message exceeds this action's length limit");
        int quantity=c.quantity()==null?1:c.quantity();
        require(quantity>=1||c.action()==ActionType.START_BUSINESS&&quantity==0,"Quantity must be at least one; START_BUSINESS allows zero employee positions");
        String origin=a.location;
        if(c.sharedPlan()!=null) {
            require(c.action()==ActionType.TALK||c.action()==ActionType.END_CHAT,"Attach a shared agreement to your own TALK or END_CHAT turn");
            ConversationPlans.validate(s,a,target,c.sharedPlan());
        }
        if(List.of(ActionType.MOVE,ActionType.RUN,ActionType.JOIN_PLAN,ActionType.TRAVEL_BUS,ActionType.WAIT_BUS,
            ActionType.BOARD_BUS,ActionType.CONTINUE_TRAVEL,ActionType.JUMP,ActionType.WORK,ActionType.REST,ActionType.WAIT_PLAN,ActionType.DO_ACTIVITY,
            ActionType.SLEEP,ActionType.USE_OBJECT,ActionType.READ_BOOK,ActionType.RESEARCH_PROJECT,ActionType.BUILD_PROJECT,ActionType.PRODUCE_PRODUCT,ActionType.BUILD_CONSTRUCTION,ActionType.PLACE_OBJECT,ActionType.MOVE_OBJECT,ActionType.WAIT_RAIN).contains(c.action()))
            require(!Conversations.active(s,a),"Before leaving or starting another activity, say a brief farewell using END_CHAT with message; then choose your next action");
        switch(c.action()) {
            case LOOK -> { PublicEncounters.ignore(s,a);Social.reviewed(s,a);return "Observation refreshed"; }
            case MOVE, RUN -> {
                Building b=Travel.destination(s,target);
                require(b!=null,"Unknown destination; use a location ID or point:x,z on dry island land");
                require(!target.startsWith("point:")||s.buildings.values().stream().noneMatch(other->Places.enclosed(other)&&Math.abs(b.x-other.x)<3.5&&Math.abs(b.z-other.z)<3.5),"Choose land outside building footprints");
                require(!target.startsWith("point:")||s.worldObjects.values().stream().noneMatch(o->Math.abs(b.x-o.x)<1&&Math.abs(b.z-o.z)<1),"Choose a destination clear of sign footprints");
                require(!(b.type.equals("apartment")||b.type.equals("house"))||target.equals(a.home),"Only your own home is accessible");
                require(!target.equals(a.location),"Already at destination");
                boolean continuing=Travel.moving(a)&&(target.equals(a.pending.target)||target.equals(a.pending.finalDestination));
                if(!continuing)Places.requireEntry(s,a,b,s.minute);
                boolean running=c.action()==ActionType.RUN;
                require(!running||a.energy>=10,"Rest before running");
                Pending p=pending(actionId,running?"RUN":"MOVE",target,0);
                p.routeChoice=present(c.item())?c.item():Travel.defaultRoute(a);
                p.route.addAll(Travel.path(s,a,b,p.routeChoice));p.frontDoorRoute=true;
                p.exitDoor=Travel.exitDoor(s,a);
                if(a.pending!=null)e.completions.add(new Completion(a.pending.id,"cancelled","Resident changed their travel plan"));
                a.pending=p; a.location="road";
                Travel.reviewed(s,a);
                e.facts.add(new Fact("movement",a.id,target,a.name+(running?" is running to ":" is walking to ")+b.name+"."));
            }
            case JUMP -> {
                require(a.energy>=2,"Rest before jumping");
                a.energy-=2;
                a.pending=pending(actionId,"JUMP",a.location,s.minute+1);
                e.facts.add(new Fact("daily_life",a.id,a.location,a.name+" jumped with joy."));
            }
            case TALK -> {
                Resident other=s.agents.get(target); together(a,other);
                require(Social.perceivable(s,a,other),"The other resident must be in the same visible space");
                require(present(c.message()),"A message is required");
                require(!Travel.moving(a)||!Social.onRoad(s,a),"Clear the road before stopping to chat");
                Conversations.speak(s,a,other,c.message());
                if(Travel.moving(a))Social.pause(s,a,other,6);
                e.facts.add(new Fact("conversation",a.id,target,a.name+" to "+other.name+": "+c.message()));
                if(c.sharedPlan()!=null)ConversationPlans.apply(s,a,target,c.sharedPlan(),actionId,e);
                return "Spoke to "+other.name+(Travel.moving(a)?"; your journey is paused for up to six minutes":"");
            }
            case CALL_OUT -> {PublicEncounters.callOut(s,a,target,c.message(),actionId,e);return "Called out; the other resident chooses whether to respond";}
            case ACKNOWLEDGE -> {PublicEncounters.acknowledge(s,a,target,c.message(),e);return "Call acknowledged; your activity continues";}
            case DECLINE_CALL -> {PublicEncounters.decline(s,a,target,c.message(),e);return "Call declined; your activity continues";}
            case PAUSE_TRAVEL -> {
                Resident other=s.agents.get(target);together(a,other);
                Social.pause(s,a,other,c.quantity()==null?6:quantity);return "Journey paused; only you have stopped";
            }
            case END_CHAT -> {
                Conversations.farewell(s,a,target,c.message(),e);
                // A targeted closing leaves other conversations open.
                if(target.isEmpty())Social.end(s,a);
                else if(!Conversations.active(s,a))Social.end(s,a);
                if(c.sharedPlan()!=null)ConversationPlans.apply(s,a,target,c.sharedPlan(),actionId,e);
                return Conversations.active(s,a)?"Chat ended; another conversation remains active":"Chat ended; continuing any paused journey";
            }
            case RELATE -> {
                Resident other=s.agents.get(target);
                require(other!=null&&!a.id.equals(target),"Choose another resident");
                require(present(c.message())&&c.message().length()<=100,"Describe your own relationship in up to 100 characters");
                Relationships.label(s,a,target,c.message());Social.reviewed(s,a);return "Private relationship updated";
            }
            case PROPOSE_PLAN -> {Social.propose(s,a,target,c.item(),c.message(),c.quantity()==null?15:quantity,actionId,e);return "Invitation created: "+actionId+"; awaiting the other resident's acceptance";}
            case ACCEPT_PLAN, DECLINE_PLAN -> {Social.respond(s,a,target,c.action()==ActionType.ACCEPT_PLAN,e);return c.action()==ActionType.ACCEPT_PLAN?"Shared plan accepted":"Invitation declined";}
            case CANCEL_PLAN -> {Social.cancel(s,a,target,e);return "Shared plan cancelled";}
            case JOIN_PLAN -> {
                SocialPlan plan=Social.plan(s,a,target);require(plan.status.equals("accepted"),"Both residents must agree before joining a plan");
                if(plan.destination.equals(a.location))return "Already at the agreed meeting place";
                Places.requireOpen(Travel.destination(s,plan.destination),s.minute);
                Pending previous=a.pending;
                Travel.startWalk(s,a,plan.destination,actionId,plan.destination,null,null);Social.reviewed(s,a);
                if(previous!=null)e.completions.add(new Completion(previous.id,"cancelled","Resident chose to join a shared plan"));
                a.pending.socialPlanId=plan.id;
                Social.joined(s,a,plan);
                e.facts.add(new Fact("movement",a.id,plan.destination,a.name+" is walking to "+Social.destinationName(s,plan.destination)+" for an agreed plan."));
            }
            case WAIT_PLAN -> {
                SocialPlan plan=Social.plan(s,a,target);
                require("accepted".equals(plan.status),"Wait for an accepted shared meeting");
                require(plan.destination.equals(a.location),"Reach the agreed meeting place before waiting");
                a.pending=pending(actionId,"PLAN_WAIT",plan.destination,plan.expires);
                a.pending.startedAt=s.minute;a.pending.detail="Waiting for the agreed meeting";a.pending.socialPlanId=plan.id;
                e.facts.add(new Fact("social_plan",a.id,a.id.equals(plan.proposer)?plan.invited:plan.proposer,
                    a.name+" is waiting at "+Social.destinationName(s,plan.destination)+" for the agreed meeting."));
            }
            case DO_ACTIVITY -> {
                return ActivityExecution.start(s,a,actionId,target,c.message(),c.item(),c.quantity()==null?15:quantity,e);
            }
            case TRANSFER -> {
                Resident other=s.agents.get(target); together(a,other);
                require(c.amount()!=null&&c.amount()>0&&a.money>=c.amount(),"Insufficient funds or invalid amount");
                pay(s,a.id,other.id,c.amount(),"gift",e);
                Relationships.record(s,"transfer:"+actionId,"transfer",a.id,other.id,a.name+" transferred "+c.amount()+" cents to "+other.name+".");
                e.facts.add(new Fact("exchange",a.id,other.id,a.name+" exchanged money with "+other.name+"."));
            }
            case BUY, SELL -> {
                if(c.action()==ActionType.BUY&&s.businesses.containsKey(target))return Ventures.buy(s,a,target,c.item(),quantity,e);
                Building shop=s.buildings.get(target); require(shop!=null,"Unknown shop"); at(a,target);
                Places.requireOpen(shop,s.minute);
                require(c.item()!=null&&shop.prices.containsKey(c.item()),"Item is not traded here");
                long price=shop.prices.get(c.item())*quantity;
                int owned=a.inventory.getOrDefault(c.item(),0),stock=shop.stock.getOrDefault(c.item(),0);
                if(c.action()==ActionType.BUY) {
                    require(stock>=quantity,"Out of stock"); require(a.money>=price,"Insufficient funds");
                    pay(s,a.id,target,price,"purchase",e); a.inventory.put(c.item(),owned+quantity); shop.stock.put(c.item(),stock-quantity);
                } else {
                    price=price/2; require(owned>=quantity,"Insufficient inventory"); require(s.accounts.getOrDefault(target,0L)-Economy.remainingPayroll(s,target)>=price,"Shop cannot afford this purchase while reserving scheduled wages");
                    pay(s,target,a.id,price,"sale",e); a.inventory.put(c.item(),owned-quantity); shop.stock.put(c.item(),stock+quantity);
                    if("raincoat".equals(c.item())&&owned==quantity)a.raincoatWorn=false;
                }
                e.facts.add(new Fact("economy",a.id,target,a.name+(c.action()==ActionType.BUY?" bought ":" sold ")+quantity+" "+c.item()+" at "+shop.name+"."));
            }
            case EAT -> {
                require("meal".equals(c.item())||"coffee".equals(c.item()),"Choose meal or coffee");
                int owned=a.inventory.getOrDefault(c.item(),0); require(owned>0,"Item is not in your inventory");
                a.inventory.put(c.item(),owned-1);
                a.hunger=Math.max(0,a.hunger-(c.item().equals("meal")?45:5));
                if(c.item().equals("meal")) {a.lastMealMinute=s.minute;a.hungerAfterLastMeal=a.hunger;}
                if(a.home.equals(a.location))a.hungerWakeNeedsFood=false;
                if(c.item().equals("coffee")) a.energy=Math.min(100,a.energy+10);
                e.facts.add(new Fact("daily_life",a.id,"",a.name+" enjoyed "+c.item()+"."));
            }
            case WEAR, REMOVE_CLOTHING -> {
                require("raincoat".equals(c.item()),"Choose item raincoat");
                require(a.inventory.getOrDefault("raincoat",0)>0,"Own a raincoat before wearing or removing it");
                require(c.action()!=ActionType.WEAR||weather(s.minute).equals("Rain"),"It is not raining; keep the raincoat in your inventory");
                a.raincoatWorn=c.action()==ActionType.WEAR;
                if(Travel.moving(a))Travel.reviewed(s,a);
                e.facts.add(new Fact("daily_life",a.id,a.location,a.name+(a.raincoatWorn?" put on a raincoat.":" removed their raincoat.")));
                return a.raincoatWorn?"Raincoat worn; protected from rain":"Raincoat removed; still in inventory";
            }
            case APPLY_JOB -> {
                Job job=s.jobs.get(target); require(job!=null,"Unknown job"); at(a,job.building);
                Places.requireOpen(s.buildings.get(job.building),s.minute);
                require(a.job.isEmpty(),"Already employed; choose QUIT_JOB before applying for another job");
                long occupied=s.agents.values().stream().filter(r->target.equals(r.job)||target.equals(r.offer)).count();
                require(target.equals(a.offer)||occupied<job.capacity,"No vacancies");
                a.offer=target;
                return "Job offer available. Use ACCEPT_JOB with " + target;
            }
            case ACCEPT_JOB -> {
                require(a.job.isEmpty(),"Already employed; choose QUIT_JOB before accepting another job");
                require(!target.isEmpty()&&target.equals(a.offer),"No matching job offer");
                Job job=s.jobs.get(target);require(job!=null,"This job offer is no longer available");at(a,job.building);
                Places.requireOpen(s.buildings.get(job.building),s.minute);
                long occupied=s.agents.values().stream().filter(r->!r.id.equals(a.id)&&(target.equals(r.job)||target.equals(r.offer))).count();
                require(occupied<job.capacity,"No vacancies remain for this job offer");
                a.job=target; a.offer=""; a.occupation=job.title;
                a.leaveDay=-1;a.workPermit=null;
                e.facts.add(new Fact("employment",a.id,job.building,a.name+" started a new job as "+job.title+"."));
            }
            case QUIT_JOB -> {return Employment.quit(s,a,target,e);}
            case REQUEST_OVERTIME -> {return WorkAccess.request(s,a);}
            case TAKE_LEAVE, CANCEL_LEAVE -> {
                require(s.jobs.containsKey(a.job),"Accept a job first");
                a.leaveDay=c.action()==ActionType.TAKE_LEAVE?Math.floorDiv(s.minute,1440):-1;
                if(c.action()==ActionType.TAKE_LEAVE)a.workPermit=null;
                return c.action()==ActionType.TAKE_LEAVE?"Unpaid leave recorded for today; choose how to spend your time":"Leave cancelled; attending work remains your choice";
            }
            case WORK -> {
                Job job=s.jobs.get(a.job); require(job!=null,"Accept a job first"); at(a,job.building);
                Places.requireEntry(s,a,s.buildings.get(job.building),s.minute);
                require(!DailyRoutines.onLeave(a,s.minute),"You are on leave today; cancel leave before choosing work");
                int duration=WorkPayroll.duration(job,s.minute,"overtime".equals(c.item()));
                require(WorkAccess.forMinutes(s,a,s.buildings.get(job.building),s.minute,duration),"The work block needs public opening or approved staff access throughout; request overtime access or choose another activity");
                require(DailyRoutines.openFor(s.buildings.get(job.building),s.minute,duration)||"overtime".equals(c.item()),"Closed-hours work requires explicit WORK item overtime");
                require(!present(c.item())||"overtime".equals(c.item()),"WORK item must be null or overtime");
                int overtime=DailyRoutines.overtimeMinutes(job,a,s.minute,duration);
                require(overtime==0||"overtime".equals(c.item()),"Extra work is optional: use WORK item overtime to choose paid extra hours");
                long day=Math.floorDiv(s.minute,1440);
                int worked=a.workDay==day?a.workedMinutes:0;
                require(a.energy>=10&&a.hunger<95,"Eat or rest before working");
                long wage=(job.hourlyWage*(duration-overtime)+DailyRoutines.overtimeHourlyWage(job)*overtime)/60;
                long regularWage=job.hourlyWage*(duration-overtime)/60;
                String employer=Economy.employer(job);
                long available=s.accounts.getOrDefault(employer,0L)-(overtime>0?Math.max(0,Economy.remainingPayroll(s,employer)-regularWage):0);
                require(available>=wage,"Employer cannot fund this shift while reserving scheduled wages");
                // Reserve wages when accepting work, so competing shifts cannot spend the same funds.
                pay(s,employer,"escrow:"+actionId,wage,overtime>0?"overtime_reservation":"wage_reservation",e);
                a.overtimeMinutes=(a.workDay==day?a.overtimeMinutes:0)+overtime;
                a.workDay=day;a.workedMinutes=worked+duration;
                a.pending=pending(actionId,"WORK",job.building,s.minute+duration);
                a.pending.startedAt=s.minute;
                a.pending.workDurationMinutes=duration;
                a.pending.trackedWork=true;
                a.pending.workHourlyWage=job.hourlyWage;
                a.pending.workOvertimeHourlyWage=DailyRoutines.overtimeHourlyWage(job);
                a.pending.workEmployer=employer;
                a.pending.overtimeMinutes=overtime;
                WorkPayroll.begin(s,a,job);
            }
            case REST -> {
                Building place=s.buildings.get(a.location);
                require(a.location.equals(a.home)||place!=null&&place.restAllowed||s.geographyVersion>=2&&IslandGeography.coordinate(a.location)!=null,"Rest at home, on dry island land or a place marked restAllowed in your observations");
                if(place!=null)Places.requireOpen(place,s.minute);
                Pending rest=pending(actionId,"REST",a.location,s.minute+30);
                Seating.reserve(s,a,rest,false);a.pending=rest;
            }
            case SLEEP -> {
                at(a,a.home);
                require(!a.hungerWakeNeedsFood,"Hunger woke you: eat a meal or drink from home supplies before sleeping again");
                require(a.hunger<DailyRoutines.SEVERE_HUNGER,"Severe hunger: eat a meal before sleeping again; buy food at an open shop if needed");
                boolean resuming=a.resumeSleepUntil>s.minute;
                long until=resuming?a.resumeSleepUntil:DailyRoutines.sleepUntil(a,s.minute,c.quantity());
                a.pending=pending(actionId,"SLEEP",a.home,until);a.pending.startedAt=s.minute;
                a.pending.mainSleep=!resuming&&(DailyRoutines.sleepingTime(a,s.minute)||a.energy<60||a.sleepDebtMinutes>=120);
                a.pending.hungerWakeThreshold=DailyRoutines.hungerWakeThreshold(a,s.minute);
                a.resumeSleepUntil=-1;
                e.facts.add(new Fact("daily_life",a.id,a.home,a.name+" went to sleep at home."));
            }
            case USE_OBJECT -> {
                String location=objectLocation(s,target);
                require(location!=null,"Choose an object listed in your observations"); at(a,location);
                Places.requireOpen(s.buildings.get(location),s.minute);
                Pending rest=pending(actionId,"REST",location,s.minute+30);
                rest.objectId=target;
                Seating.reserve(s,a,rest,true);a.pending=rest;
                e.facts.add(new Fact("daily_life",a.id,target,a.name+" chose to sit at "+s.buildings.get(location).name+"."));
            }
            case READ_BOOK -> Library.read(s,a,target,c.quantity()==null?30:quantity,actionId,e);
            case BORROW_BOOK -> {return Library.borrow(s,a,target,e);}
            case RETURN_BOOK -> {return Library.returnBook(s,a,target,e);}
            case REASON -> {return Learning.reason(s,a,target,c.message(),actionId,e);}
            case CREATE_WORK -> {return Learning.create(s,a,target,c.item(),c.message(),actionId,e);}
            case DECLARE_INTEREST -> {return Projects.declareInterest(s,a,target,c.item(),e);}
            case START_PROJECT -> {return Projects.start(s,a,target,c.item(),c.message(),actionId,e);}
            case INVITE_PROJECT -> {return Projects.invite(s,a,target,c.item(),c.message(),actionId,e);}
            case ACCEPT_PROJECT, DECLINE_PROJECT -> {return Projects.respond(s,a,target,c.action()==ActionType.ACCEPT_PROJECT,e);}
            case RESEARCH_PROJECT -> {return Projects.research(s,a,target,c.item(),c.quantity()==null?30:quantity,actionId,e);}
            case BUILD_PROJECT -> {return Projects.build(s,a,target,actionId,e);}
            case USE_PRODUCT -> {return Projects.use(s,a,target,e);}
            case ENDORSE_BUSINESS -> {return Ventures.endorse(s,a,target,e);}
            case LAUNCH_BUSINESS -> {return Ventures.launch(s,a,target,c.message(),c.quantity()==null?1:quantity,c.amount()==null?20000:c.amount(),c.loanYears(),actionId,e);}
            case FUND_BUSINESS -> {return Ventures.fund(s,a,target,c.amount(),e);}
            case SET_PRODUCT_PRICE -> {return Ventures.price(s,a,target,c.item(),c.amount(),e);}
            case PRODUCE_PRODUCT -> {return Ventures.produce(s,a,target,quantity,actionId,e);}
            case WITHDRAW_PROFIT -> {return Ventures.withdraw(s,a,target,c.amount(),e);}
            case START_BUSINESS -> {return Ventures.startSmallBusiness(s,a,target,c.item(),c.message(),c.quantity(),c.amount(),c.loanYears(),actionId,e);}
            case START_CONSTRUCTION -> {return Construction.start(s,a,target,c.item(),c.message(),c.amount(),c.loanYears(),c.laborMode(),actionId,e);}
            case BUILD_CONSTRUCTION -> {return Construction.build(s,a,target,actionId,c.item(),e);}
            case REPAY_LOAN -> {return Loans.repay(s,a,target,c.amount(),e);}
            case PLACE_OBJECT -> {return WorldObjects.place(s,a,target,c.item(),c.message(),actionId,e);}
            case MOVE_OBJECT -> {return WorldObjects.move(s,a,target,c.item(),actionId,e);}
            case TRAVEL_BUS -> Travel.startBus(s,a,target,c.item(),actionId,e);
            case BOARD_BUS, WAIT_BUS -> Traffic.board(s,a,target,c.item(),actionId,e,c.action()==ActionType.WAIT_BUS);
            case CONTINUE_TRAVEL -> {
                require(Travel.moving(a),"There is no journey to continue");PublicEncounters.ignore(s,a);Social.end(s,a);Travel.reviewed(s,a);return "Continuing the chosen journey";}
            case WAIT_RAIN -> {
                require(Travel.sheltered(s,a),"Move to a sheltered destination before waiting out rain");
                require(weather(s.minute).equals("Rain"),"It is not raining");
                a.pending=pending(actionId,"WAIT_RAIN",a.location,s.minute+30);
                e.facts.add(new Fact("daily_life",a.id,a.location,a.name+" is waiting under shelter for the rain to ease."));
            }
        }
        Conversations.travelResults(s,e);
        if(a.pending!=null&&actionId.equals(a.pending.id)&&List.of(ActionType.MOVE,ActionType.RUN,ActionType.JOIN_PLAN,
            ActionType.TRAVEL_BUS,ActionType.WAIT_BUS,ActionType.BOARD_BUS).contains(c.action()))Conversations.startedTravel(s,a,origin);
        // Release the turn-taking lock while preserving the exchange and witnessed travel.
        if(a.pending!=null&&a.pending.socialWith==null)Conversations.end(s,a);
        return a.pending==null?"Action completed":"Action accepted";
    }
    public static String objectLocation(State state,String object) {
        return state.buildings.values().stream().filter(b->b.objects.containsKey(object)).map(b->b.id).findFirst().orElse(null);
    }
    static Pending pending(String id,String type,String target,long until) { Pending p=new Pending(); p.id=id;p.type=type;p.target=target;p.until=until;return p; }
    static void pay(State s,String source,String destination,long amount,String reason,Effects e) {
        if(s.agents.containsKey(source)) s.agents.get(source).money-=amount;
        else s.accounts.put(source,s.accounts.getOrDefault(source,0L)-amount);
        if(s.agents.containsKey(destination)) s.agents.get(destination).money+=amount;
        else s.accounts.put(destination,s.accounts.getOrDefault(destination,0L)+amount);
        e.payments.add(new Payment(source,destination,amount,reason));
    }
    public static Effects tick(State s) {
        s.minute++; Effects e=new Effects();
        Economy.settle(s,e);
        List<Traffic.Vehicle> vehicles=Traffic.vehicles(s);
        List<Traffic.RoadMotion> crossingTraffic=Traffic.crossingTraffic(s);
        String previousWeather=weather(s.minute-1),currentWeather=weather(s.minute);
        if(!previousWeather.equals(currentWeather)) {
            String message=switch(currentWeather) {
                case "Rain"->"Rain is falling over New Eden.";
                case "Overcast"->"Clouds have gathered over New Eden.";
                default->"The skies have cleared over New Eden.";
            };
            e.facts.add(new Fact("weather","","",message));
        }
        for(Resident a:s.agents.values())a.activityMotion=new ArrayList<>();
        for(Resident a:s.agents.values()) {
            if(a.performanceUntil<s.minute)a.recentPerformance=null;
            DailyLife.tick(a,s.minute);
            Library.dueNotices(s,a,e);
            if(!DailyRoutines.sleepingTime(a,s.minute)&&!(a.bedtimeRoutine&&Travel.moving(a)
                &&(Objects.equals(a.home,a.pending.target)||Objects.equals(a.home,a.pending.finalDestination))))a.bedtimeRoutine=false;
            if(a.nightPlan!=null&&a.nightPlan.until<=s.minute) a.nightPlan=null;
            if(a.workPermit!=null&&a.workPermit.until<=s.minute)a.workPermit=null;
            a.hunger=Math.min(100,a.hunger+(a.pending!=null&&a.pending.type.equals("SLEEP")?0.015:0.035));
            a.energy=Math.max(0,a.energy-0.025);
            if(a.inventory.getOrDefault("raincoat",0)==0)a.raincoatWorn=false;
            if(a.raincoatWorn&&!currentWeather.equals("Rain")) {
                a.raincoatWorn=false;
                e.facts.add(new Fact("daily_life",a.id,a.location,a.name+" removed their raincoat because it is no longer raining."));
            }
            boolean exposed=currentWeather.equals("Rain")&&!Travel.sheltered(s,a)&&!a.raincoatWorn&&a.weatherProtectionUntil<=s.minute;
            a.wetness=Math.max(0,Math.min(100,a.wetness+(exposed?1:-2)));
            Pending p=a.pending;
            if(p!=null) {
                if(ActionPerformance.beforeTick(s,a,e)) {
                    // An intentional break advances the world, but no productive work.
                } else if(Seating.approach(s,a,vehicles,crossingTraffic)) {
                    // Reaching furniture is physical movement, not a reading or recovery minute.
                } else if(Workspaces.approach(s,a,vehicles,crossingTraffic)) {
                    // A task begins at its real workstation, after reaching it.
                    if("WORK".equals(p.type)&&s.minute>=p.until)Employment.stopWork(s,a,e,"Chosen work block ended while reaching its workstation");
                } else if(p.type.equals("WORK")&&p.trackedWork) {
                    WorkPayroll.tick(s,a,e);
                } else if(p.type.equals("READ_BOOK")) {
                    Library.tickReading(s,a,e);
                } else if(List.of("RESEARCH_PROJECT","BUILD_PROJECT").contains(p.type)) {
                    Projects.tick(s,a,e);
                } else if(p.type.equals("PRODUCE_PRODUCT")) {
                    Ventures.tick(s,a,e);
                } else if(p.type.equals("BUILD_CONSTRUCTION")) {
                    Construction.tick(s,a,e);
                } else if(List.of("PLACE_OBJECT","MOVE_OBJECT").contains(p.type)) {
                    WorldObjects.tick(s,a,e);
                } else if(p.type.equals("DO_ACTIVITY")) {
                    ActivityExecution.tick(s,a,e,vehicles,crossingTraffic);
                } else if(p.type.equals("RIDE_BUS")||p.type.equals("WAIT_BUS")||p.type.equals("BOARD_BUS")) {
                    Traffic.tickPassenger(s,a,e);
                } else if(p.type.equals("MOVE")||p.type.equals("RUN")) {
                    if(p.entering&&!Places.canEnter(s,a,Travel.walkingDestination(s,p),s.minute)) {
                        Travel.walkingArrived(s,a,e);
                    } else if(p.socialUntil<=s.minute) {
                        if(p.socialWith!=null)Social.end(s,a);
                        if(!p.frontDoorRoute) {p.exitDoor=Travel.exitDoor(s,a);p.route=Travel.path(s,a,Travel.walkingDestination(s,p),p.routeChoice==null?"shortest":p.routeChoice);p.frontDoorRoute=true;}
                        if(!p.entering&&p.exitDoor==null)p.route=Travel.remainingPath(a,Travel.walkingDestination(s,p),p.route);
                        boolean running=p.type.equals("RUN")&&a.energy>=1;
                        if(running) a.energy=Math.max(0,a.energy-.2);
                        else if(p.type.equals("RUN")) p.type="MOVE";
                        p.motion.clear();
                        walkRoute(s,a,p,vehicles,crossingTraffic,running,(x,z)->true);
                        a.activityMotion=new ArrayList<>(p.motion);
                        if(p.route.isEmpty()) Travel.walkingArrived(s,a,e);
                    }
                } else {
                    p.activeMinutes++;
                    if(p.type.equals("SLEEP")) a.energy=Math.min(100,a.energy+0.8);
                    if(p.type.equals("REST")) a.energy=Math.min(100,a.energy+0.5);
                    if(p.type.equals("SLEEP")&&s.minute>=p.until&&a.energy<80&&s.minute<p.startedAt+600&&a.hunger<95)p.until=Math.min(p.startedAt+600,s.minute+15);
                    boolean hungryWake=p.type.equals("SLEEP")&&s.minute<p.until&&a.hunger>=p.hungerWakeThreshold;
                    if(s.minute>=p.until||p.type.equals("WAIT_RAIN")&&!currentWeather.equals("Rain")||hungryWake) {
                        if(p.type.equals("WORK")) {
                            WorkPayroll.completeBlock(s,a,e);
                        }
                        if(p.type.equals("SLEEP")) {
                            int slept=(int)(s.minute-p.startedAt);
                            if(p.mainSleep) {
                                int preferred=a.routine==null?480:Math.floorMod(a.routine.wakeMinute-a.routine.sleepMinute,1440);
                                a.sleepDebtMinutes=Math.max(0,Math.min(1440,a.sleepDebtMinutes+preferred-slept));
                            }else a.sleepDebtMinutes=Math.max(0,a.sleepDebtMinutes-slept);
                            a.lastWakeMinute=s.minute;a.bedtimeRoutine=false;
                            if(hungryWake) {
                                a.resumeSleepUntil=p.until;a.hungerWakeUntil=p.until;
                                a.hungerWakeNeedsFood=true;a.bedtimeRoutine=true;
                            }
                            e.facts.add(new Fact("daily_life",a.id,a.home,a.name+" woke up at home after "+slept+" minutes of sleep"+(hungryWake?" because of severe hunger.":".")));
                        }
                        if(a.pending!=null)finish(a,e,hungryWake?"SLEEP ended due to severe hunger; eat a meal before sleeping again":p.type+" completed");
                    }
                }
            }
            // Credit the final open minute and complete due work before enforcing closure.
            Places.closeVisit(s,a,e);
            WorkPayroll.settle(s,a,e);
            if(s.minute%1440==0) {
                Construction.Site home=s.construction.get(a.home);
                boolean ownsHome=home!=null&&a.id.equals(home.sponsor)&&"completed".equals(home.status);
                long due=(ownsHome?0:2000)+a.arrears,paid=Math.min(a.money,due);
                if(paid>0) pay(s,a.id,"housing",paid,"rent",e);
                a.arrears=due-paid;
                e.facts.add(new Fact("daily_life",a.id,a.home,a.name+" began a new day in New Eden."));
            }
        }
        Conversations.travelResults(s,e);
        Conversations.tick(s);
        PublicEncounters.tick(s);
        Social.tick(s,e);
        return e;
    }
    /** Shared small-step movement keeps local activities subject to real traffic and terrain. */
    static double walkRoute(State s,Resident a,Pending p,List<Traffic.Vehicle> vehicles,List<Traffic.RoadMotion> crossingTraffic,
                            boolean running,java.util.function.BiPredicate<Double,Double> allowed) {
        return walkRouteBudget(s,a,p,vehicles,crossingTraffic,running,allowed,
            running?CityLayout.of(s).runningSpeed():CityLayout.of(s).walkingSpeed()).distance();
    }
    record WalkingStep(double distance,double effort) {}
    static WalkingStep walkRouteBudget(State s,Resident a,Pending p,List<Traffic.Vehicle> vehicles,List<Traffic.RoadMotion> crossingTraffic,
                            boolean running,java.util.function.BiPredicate<Double,Double> allowed,double permittedBudget) {
        double budget=Math.min(permittedBudget,running?CityLayout.of(s).runningSpeed():CityLayout.of(s).walkingSpeed()),initialBudget=budget,moved=0;
        if(allowed!=null&&p.motion.isEmpty())p.motion.add(new Traffic.Pose(a.x,a.z,a.facingHeading==null?0:a.facingHeading));
        while(budget>0&&!p.route.isEmpty()) {
            double[] point=p.route.getFirst();double d=distance(a.x,a.z,point[0],point[1]);
            if(d<.001){p.route.removeFirst();continue;}
            // Small steps prevent RUN from jumping over a crosswalk check.
            double step=Math.min(.5,Math.min(d,budget));TerrainElevation.Segment terrain=null;
            if(s.geographyVersion>=4) {
                step=Math.min(.5,d);
                double tx=a.x+(point[0]-a.x)*step/d,tz=a.z+(point[1]-a.z)*step/d;
                terrain=TerrainElevation.segment(a.x,a.z,tx,tz);
                if(terrain.walkingEffort()>budget) {
                    double low=0,high=step;
                    for(int trial=0;trial<18;trial++) {
                        double candidate=(low+high)/2,cx=a.x+(point[0]-a.x)*candidate/d,cz=a.z+(point[1]-a.z)*candidate/d;
                        if(TerrainElevation.segmentEffort(a.x,a.z,cx,cz)<=budget)low=candidate;else high=candidate;
                    }
                    step=low;terrain=TerrainElevation.segment(a.x,a.z,a.x+(point[0]-a.x)*step/d,a.z+(point[1]-a.z)*step/d);
                }
                if(step<1e-7)break;
            }
            double nx=a.x+(point[0]-a.x)*step/d,nz=a.z+(point[1]-a.z)*step/d;
            if(allowed!=null&&!allowed.test(nx,nz)) {p.waitingReason="Activity path is obstructed; no progress through the obstacle";break;}
            boolean safe=vehicles.stream().noneMatch(v->{
                double dx=nx-v.x(),dz=nz-v.z(),across=dx*Math.cos(v.heading())-dz*Math.sin(v.heading()),along=dx*Math.sin(v.heading())+dz*Math.cos(v.heading());
                return Math.abs(across)<1.05&&Math.abs(along)<(v.type().equals("bus")?2.8:1.7);
            });
            if(!safe||!Traffic.canWalk(CityLayout.of(s),a.x,a.z,nx,nz,crossingTraffic)) {p.waitingReason="Waiting for traffic to clear this crossing";break;}
            if(allowed!=null) {a.facingHeading=Math.atan2(nx-a.x,nz-a.z);a.facingUntil=s.minute+2;}
            p.waitingReason=null;a.x=nx;a.z=nz;budget-=terrain==null?step:terrain.walkingEffort();moved+=step;
            if(allowed!=null)p.motion.add(new Traffic.Pose(a.x,a.z,Math.atan2(nx-p.motion.getLast().x(),nz-p.motion.getLast().z())));
            if(running&&terrain!=null)a.energy=Math.max(0,a.energy-.2*(terrain.runningEnergyEffort()-terrain.walkingEffort())/CityLayout.of(s).runningSpeed());
            if(p.entering)a.location=p.target;
            if(d<=step+.001) {
                p.route.removeFirst();
                if(p.exitDoor!=null&&distance(a.x,a.z,p.exitDoor[0],p.exitDoor[1])<.001){p.exitDoor=null;break;}
            }
        }
        return new WalkingStep(moved,initialBudget-budget);
    }
    static void finish(Resident a,Effects e,String result) { e.completions.add(new Completion(a.pending.id,"completed",result)); a.pending=null; }
}
