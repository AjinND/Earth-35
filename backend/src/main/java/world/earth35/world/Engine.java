package world.earth35.world;

import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;
import java.util.*;

/** Deterministic mechanics: no model calls, network, wall clock, or database access. */
public class Engine {
    public record Fact(String kind,String actor,String target,String message) {}
    public record Payment(String source,String destination,long amount,String reason) {}
    public record Completion(String id,String status,String result) {}
    public static class Effects {
        public List<Fact> facts=new ArrayList<>();
        public List<Payment> payments=new ArrayList<>();
        public List<Completion> completions=new ArrayList<>();
    }
    public static class Invalid extends RuntimeException { public Invalid(String message) { super(message); } }
    static void require(boolean condition,String message) { if(!condition) throw new Invalid(message); }
    static boolean present(String value) { return value!=null&&!value.isBlank(); }
    static void at(Resident a,String location) { require(location.equals(a.location),"Move to " + location + " first"); }
    static void together(Resident a,Resident b) { require(b!=null&&!a.id.equals(b.id)&&distance(a.x,a.z,b.x,b.z)<=10,"Target must be another nearby resident"); }
    static double distance(double x,double z,double bx,double bz) { return Math.hypot(x-bx,z-bz); }
    public static String activity(Resident a) { return a.pending==null?"Idle":a.pending.socialWith!=null?"CHAT":a.pending.type; }
    public static boolean busy(Resident a) {
        return a!=null&&a.pending!=null&&List.of("SLEEP","WORK","BOARD_BUS","RIDE_BUS","READ_BOOK","RESEARCH_PROJECT","BUILD_PROJECT","PRODUCE_PRODUCT","BUILD_CONSTRUCTION","PLACE_OBJECT","MOVE_OBJECT").contains(a.pending.type);
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
        require(!a.paused,"Agent participation is paused");
        require(a.pending==null||!busy(a)&&List.of(ActionType.LOOK,ActionType.CALL_OUT,ActionType.ACKNOWLEDGE,ActionType.DECLINE_CALL).contains(c.action())||c.action()==ActionType.QUIT_JOB&&("WORK".equals(a.pending.type)||Travel.moving(a))||Travel.moving(a)&&List.of(ActionType.MOVE,ActionType.RUN,ActionType.TRAVEL_BUS,ActionType.CONTINUE_TRAVEL,
            ActionType.PAUSE_TRAVEL,ActionType.END_CHAT,ActionType.TALK,ActionType.LOOK,ActionType.RELATE,
            ActionType.PROPOSE_PLAN,ActionType.ACCEPT_PLAN,ActionType.DECLINE_PLAN,ActionType.CANCEL_PLAN,ActionType.JOIN_PLAN,ActionType.WEAR,ActionType.REMOVE_CLOTHING).contains(c.action()),"An action is already in progress");
        String target=c.target()==null?"":c.target();
        require(c.message()==null||c.message().length()<=(c.action()==ActionType.REASON||c.action()==ActionType.CREATE_WORK?8000:1000),"Message exceeds this action's length limit");
        int quantity=c.quantity()==null?1:c.quantity();
        require(quantity>=1||c.action()==ActionType.START_BUSINESS&&quantity==0,"Quantity must be at least one; START_BUSINESS allows zero employee positions");
        String origin=a.location;
        if(List.of(ActionType.MOVE,ActionType.RUN,ActionType.JOIN_PLAN,ActionType.TRAVEL_BUS,ActionType.WAIT_BUS,
            ActionType.BOARD_BUS,ActionType.CONTINUE_TRAVEL,ActionType.JUMP,ActionType.WORK,ActionType.REST,
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
                return "Spoke to "+other.name+(Travel.moving(a)?"; your journey is paused for up to six minutes":"");
            }
            case CALL_OUT -> {PublicEncounters.callOut(s,a,target,c.message(),actionId,e);return "Called out; the other resident chooses whether to respond";}
            case ACKNOWLEDGE -> {PublicEncounters.acknowledge(s,a,target,c.message(),e);return "Call acknowledged; your activity continues";}
            case DECLINE_CALL -> {PublicEncounters.decline(s,a,target,c.message(),e);return "Call declined; your activity continues";}
            case PAUSE_TRAVEL -> {Social.pause(s,a,s.agents.get(target),c.quantity()==null?6:quantity);return "Journey paused; only you have stopped";}
            case END_CHAT -> {
                Conversations.farewell(s,a,target,c.message(),e);
                // A targeted closing leaves other conversations open.
                if(target.isEmpty())Social.end(s,a);
                else if(!Conversations.active(s,a))Social.end(s,a);
                return Conversations.active(s,a)?"Chat ended; another conversation remains active":"Chat ended; continuing any paused journey";
            }
            case RELATE -> {
                Resident other=s.agents.get(target);
                require(other!=null&&!a.id.equals(target),"Choose another resident");
                require(s.conversations.containsKey(Conversations.key(a.id,target)),"Talk to this resident before recording a relationship");
                require(present(c.message())&&c.message().length()<=100,"Describe your own relationship in up to 100 characters");
                a.relationships.put(target,c.message().strip());Social.reviewed(s,a);return "Private relationship updated";
            }
            case PROPOSE_PLAN -> {Social.propose(s,a,target,c.item(),c.message(),c.quantity()==null?15:quantity,actionId,e);return "Invitation created: "+actionId+"; awaiting the other resident's acceptance";}
            case ACCEPT_PLAN, DECLINE_PLAN -> {Social.respond(s,a,target,c.action()==ActionType.ACCEPT_PLAN,e);return c.action()==ActionType.ACCEPT_PLAN?"Shared plan accepted":"Invitation declined";}
            case CANCEL_PLAN -> {Social.cancel(s,a,target,e);return "Shared plan cancelled";}
            case JOIN_PLAN -> {
                SocialPlan plan=Social.plan(s,a,target);require(plan.status.equals("accepted"),"Both residents must agree before joining a plan");
                if(plan.destination.equals(a.location))return "Already at the agreed meeting place";
                Places.requireOpen(s.buildings.get(plan.destination),s.minute);
                if(a.pending!=null)e.completions.add(new Completion(a.pending.id,"cancelled","Resident chose to join a shared plan"));
                Travel.startWalk(s,a,plan.destination,actionId,plan.destination,null,null);Social.reviewed(s,a);
                a.pending.socialPlanId=plan.id;
                e.facts.add(new Fact("movement",a.id,plan.destination,a.name+" is walking to "+s.buildings.get(plan.destination).name+" for an agreed plan."));
            }
            case TRANSFER -> {
                Resident other=s.agents.get(target); together(a,other);
                require(c.amount()!=null&&c.amount()>0&&a.money>=c.amount(),"Insufficient funds or invalid amount");
                pay(s,a.id,other.id,c.amount(),"gift",e);
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
                require(WorkAccess.fullHour(s,a,s.buildings.get(job.building),s.minute),"A full hour of public opening or approved staff access is required; request overtime access or choose another activity");
                require(DailyRoutines.fullHourOpen(s.buildings.get(job.building),s.minute)||"overtime".equals(c.item()),"Closed-hours work requires explicit WORK item overtime");
                require(!present(c.item())||"overtime".equals(c.item()),"WORK item must be null or overtime");
                int overtime=DailyRoutines.overtimeMinutes(job,a,s.minute);
                require(overtime==0||"overtime".equals(c.item()),"Extra work is optional: use WORK item overtime to choose paid extra hours");
                long day=Math.floorDiv(s.minute,1440);
                int worked=a.workDay==day?a.workedMinutes:0;
                require(a.energy>=10&&a.hunger<95,"Eat or rest before working");
                long wage=(job.hourlyWage*(60-overtime)+DailyRoutines.overtimeHourlyWage(job)*overtime)/60;
                long regularWage=job.hourlyWage*(60-overtime)/60;
                String employer=Economy.employer(job);
                long available=s.accounts.getOrDefault(employer,0L)-(overtime>0?Math.max(0,Economy.remainingPayroll(s,employer)-regularWage):0);
                require(available>=wage,"Employer cannot fund this shift while reserving scheduled wages");
                // Reserve wages when accepting work, so competing shifts cannot spend the same funds.
                pay(s,employer,"escrow:"+actionId,wage,overtime>0?"overtime_reservation":"wage_reservation",e);
                a.overtimeMinutes=(a.workDay==day?a.overtimeMinutes:0)+overtime;
                a.workDay=day;a.workedMinutes=worked+60;
                a.pending=pending(actionId,"WORK",job.building,s.minute+60);
                a.pending.startedAt=s.minute;
                a.pending.overtimeMinutes=overtime;
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
            case BUILD_CONSTRUCTION -> {return Construction.build(s,a,target,actionId,e);}
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
        for(Resident a:s.agents.values()) {
            Library.dueNotices(s,a,e);
            if(!DailyRoutines.sleepingTime(a,s.minute)) a.bedtimeRoutine=false;
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
                if(p.type.equals("READ_BOOK")) {
                    Library.tickReading(s,a,e);
                } else if(List.of("RESEARCH_PROJECT","BUILD_PROJECT").contains(p.type)) {
                    Projects.tick(s,a,e);
                } else if(p.type.equals("PRODUCE_PRODUCT")) {
                    Ventures.tick(s,a,e);
                } else if(p.type.equals("BUILD_CONSTRUCTION")) {
                    Construction.tick(s,a,e);
                } else if(List.of("PLACE_OBJECT","MOVE_OBJECT").contains(p.type)) {
                    WorldObjects.tick(s,a,e);
                } else if(p.type.equals("RIDE_BUS")||p.type.equals("WAIT_BUS")||p.type.equals("BOARD_BUS")) {
                    Traffic.tickPassenger(s,a,e);
                } else if(p.type.equals("MOVE")||p.type.equals("RUN")) {
                    if(p.socialUntil<=s.minute) {
                        if(p.socialWith!=null)Social.end(s,a);
                        if(!p.frontDoorRoute) {p.exitDoor=Travel.exitDoor(s,a);p.route=Travel.path(s,a,Travel.walkingDestination(s,p),p.routeChoice==null?"shortest":p.routeChoice);p.frontDoorRoute=true;}
                        if(!p.entering&&p.exitDoor==null)p.route=Travel.remainingPath(a,Travel.walkingDestination(s,p),p.route);
                        boolean running=p.type.equals("RUN")&&a.energy>=1;
                        double budget=running?CityLayout.of(s).runningSpeed():CityLayout.of(s).walkingSpeed();
                        if(running) a.energy=Math.max(0,a.energy-.2);
                        else if(p.type.equals("RUN")) p.type="MOVE";
                        while(budget>0&&!p.route.isEmpty()) {
                            double[] point=p.route.getFirst(); double d=distance(a.x,a.z,point[0],point[1]);
                            if(d<.001){p.route.removeFirst();continue;}
                            // Small steps prevent RUN from jumping over a crosswalk check.
                            double step=Math.min(.5,Math.min(d,budget));
                            TerrainElevation.Segment terrain=null;
                            if(s.geographyVersion>=4) {
                                step=Math.min(.5,d);
                                double tx=a.x+(point[0]-a.x)*step/d,tz=a.z+(point[1]-a.z)*step/d;
                                terrain=TerrainElevation.segment(a.x,a.z,tx,tz);
                                if(terrain.walkingEffort()>budget) {
                                    // Fit the last step to this minute's effort, including a
                                    // varying slope or bridge ramp instead of overshooting it.
                                    double low=0,high=step;
                                    for(int trial=0;trial<18;trial++) {
                                        double candidate=(low+high)/2;
                                        double cx=a.x+(point[0]-a.x)*candidate/d,cz=a.z+(point[1]-a.z)*candidate/d;
                                        if(TerrainElevation.segmentEffort(a.x,a.z,cx,cz)<=budget)low=candidate;else high=candidate;
                                    }
                                    step=low;
                                    terrain=TerrainElevation.segment(a.x,a.z,a.x+(point[0]-a.x)*step/d,a.z+(point[1]-a.z)*step/d);
                                }
                                if(step<1e-7)break;
                            }
                            double nx=a.x+(point[0]-a.x)*step/d,nz=a.z+(point[1]-a.z)*step/d;
                            boolean safe=vehicles.stream().noneMatch(v->{
                                double dx=nx-v.x(),dz=nz-v.z();
                                double across=dx*Math.cos(v.heading())-dz*Math.sin(v.heading());
                                double along=dx*Math.sin(v.heading())+dz*Math.cos(v.heading());
                                return Math.abs(across)<1.05&&Math.abs(along)<(v.type().equals("bus")?2.8:1.7);
                            });
                            if(!safe||!Traffic.canWalk(CityLayout.of(s),a.x,a.z,nx,nz,crossingTraffic)) {p.waitingReason="Waiting for traffic to clear this crossing";break;}
                            p.waitingReason=null;a.x=nx;a.z=nz;budget-=terrain==null?step:terrain.walkingEffort();
                            if(running&&terrain!=null)a.energy=Math.max(0,a.energy-.2*(terrain.runningEnergyEffort()-terrain.walkingEffort())/CityLayout.of(s).runningSpeed());
                            if(p.entering)a.location=p.target;
                            if(d<=step+.001) {
                                p.route.removeFirst();
                                if(p.exitDoor!=null&&distance(a.x,a.z,p.exitDoor[0],p.exitDoor[1])<.001){p.exitDoor=null;break;}
                            }
                        }
                        if(p.route.isEmpty()) Travel.walkingArrived(s,a,e);
                    }
                } else {
                    if(p.type.equals("SLEEP")) a.energy=Math.min(100,a.energy+0.8);
                    if(p.type.equals("REST")) a.energy=Math.min(100,a.energy+0.5);
                    if(p.type.equals("SLEEP")&&s.minute>=p.until&&a.energy<80&&s.minute<p.startedAt+600&&a.hunger<95)p.until=Math.min(p.startedAt+600,s.minute+15);
                    boolean hungryWake=p.type.equals("SLEEP")&&s.minute<p.until&&a.hunger>=p.hungerWakeThreshold;
                    if(s.minute>=p.until||p.type.equals("WAIT_RAIN")&&!currentWeather.equals("Rain")||hungryWake) {
                        if(p.type.equals("WORK")) {
                            long wage=s.accounts.getOrDefault("escrow:"+p.id,0L);
                            pay(s,"escrow:"+p.id,a.id,wage,p.overtimeMinutes>0?"overtime_wage":"wage",e); s.accounts.remove("escrow:"+p.id);
                            e.facts.add(new Fact("employment",a.id,p.target,a.name+(p.overtimeMinutes>0?" completed a paid shift including overtime.":" completed a paid shift.")));
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
                        finish(a,e,hungryWake?"SLEEP ended due to severe hunger; eat a meal before sleeping again":p.type+" completed");
                    }
                }
            }
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
    static void finish(Resident a,Effects e,String result) { e.completions.add(new Completion(a.pending.id,"completed",result)); a.pending=null; }
}
