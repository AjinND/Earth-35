import {time} from "@/lib/api";
import type {Detail,Routine} from "@/lib/types";

export default function RoutinePanel({routine,work,sleepDebt=0}:{routine:Routine|null;work:Detail["workSchedule"];sleepDebt?:number}) {
  if(!routine)return null;
  return <section className="surface routine-panel"><h2>Daily routine</h2>
    <p>Preferred sleep {time(routine.sleepMinute)}–{time(routine.wakeMinute)} · Morning: {routine.morningActivity}</p>
    <p className="muted">Chosen by this resident. Energy, wetness and lost sleep can extend recovery after a late night.</p>
    <p className="muted">Free time and work attendance are this resident's choice. Morning activities are optional; extra work earns overtime.</p>
    {sleepDebt>0&&<p>Sleep debt: {Math.round(sleepDebt/60*10)/10} hours</p>}
    {work&&<p>Work {time(work.startMinute)}–{time(work.endMinute)} · {work.workDays.map(d=>["Mon","Tue","Wed","Thu","Fri","Sat","Sun"][d]).join(", ")}<br/><small>{work.onLeave?"On unpaid leave today":work.open?"Scheduled work is available":"Next work time: "+time(work.nextWorkMinute)} · {work.minutesWorkedToday/60} hours worked today{Boolean(work.overtimeMinutesToday)&&` · ${(work.overtimeMinutesToday??0)/60} overtime hours`}</small></p>}
    {work?.staffAccess&&<p className="muted">{work.staffAccess.approved?`Overtime staff access approved until ${time(work.staffAccess.approvedUntil??0)}`:work.staffAccess.reason}</p>}
  </section>;
}
