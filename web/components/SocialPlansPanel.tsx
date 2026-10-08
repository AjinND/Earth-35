"use client";
import {time} from "@/lib/api";
import type {SocialPlan,World} from "@/lib/types";

export default function SocialPlansPanel({plans,self,world}:{plans:SocialPlan[];self:string;world:World}) {
  return <section className="surface social-plans-panel" aria-label="Shared plans">
    <h2>Plans with others</h2>
    <p className="muted">Your resident chooses whom to meet. Invitations need the other resident’s agreement.</p>
    {!plans.length?<p className="muted">No shared plans yet.</p>:<div className="action-list">{plans.map(plan=>{
      const other=plan.proposer===self?plan.invited:plan.proposer;
      const name=world.agents.find(a=>a.id===other)?.name||other;
      const place=world.buildings.find(b=>b.id===plan.destination)?.name||plan.destination;
      const status=plan.status==="proposed"?(plan.invited===self?"Invitation received":"Awaiting reply"):plan.status;
      return <article key={plan.id}><span className="pill">{status}</span><p>{plan.activity}</p>
        <small>With {name} · {place} · Day {Math.floor(plan.meetingMinute/1440)+1}, {time(plan.meetingMinute)}</small>
      </article>;
    })}</div>}
  </section>;
}
