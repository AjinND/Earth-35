"use client";
import {time} from "@/lib/api";
import type {SocialPlan,World} from "@/lib/types";

function destinationName(world:World,destination:string) {
  const place=world.buildings.find(b=>b.id===destination)?.name;
  if(place)return place;
  const point=/^point:([-+]?\d+(?:\.\d+)?),([-+]?\d+(?:\.\d+)?)$/.exec(destination);
  return point?`Outdoor meeting point (${point[1]}, ${point[2]})`:destination;
}

export default function SocialPlansPanel({plans,self,world}:{plans:SocialPlan[];self:string;world:World}) {
  return <section className="surface social-plans-panel" aria-label="Shared plans">
    <h2>Plans with others</h2>
    <p className="muted">Your resident chooses whom to meet. Invitations need the other resident’s agreement.</p>
    {!plans.length?<p className="muted">No shared plans yet.</p>:<div className="action-list">{plans.map(plan=>{
      const other=plan.proposer===self?plan.invited:plan.proposer;
      const name=world.agents.find(a=>a.id===other)?.name||other;
      const place=destinationName(world,plan.destination);
      const status=plan.status==="proposed"?(plan.invited===self?"Invitation received":"Awaiting reply"):plan.status==="in_progress"?"In progress":plan.status;
      const task=plan.tasks?.[self];
      return <article key={plan.id}><span className="pill">{status}</span><p>{plan.activity}</p>
        <small>With {name} · {place} · Day {Math.floor(plan.meetingMinute/1440)+1}, {time(plan.meetingMinute)}</small>
        {task&&<p className="muted">Your activity: {task.activity} · {destinationName(world,task.destination)}{!task.action||["DO_ACTIVITY","READ_BOOK"].includes(task.action)?` · ${task.durationMinutes} min`:""}{plan.finished?.includes(self)?" · Completed":""}</p>}
        {plan.blocked?.[self]&&<p className="muted">Waiting to proceed: {plan.blocked[self]}</p>}
      </article>;
    })}</div>}
  </section>;
}
