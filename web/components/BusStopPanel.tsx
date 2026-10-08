import {money,time} from "@/lib/api";
import type {World} from "@/lib/types";

export default function BusStopPanel({world,stop}:{world:World;stop:string}) {
  const transport=world.transport;if(!transport)return null;
  const routes=transport.routes.filter(r=>r.stops.slice(0,-1).includes(stop));
  const departures=transport.departures.filter(d=>d.stop===stop).slice(0,4);
  const name=(id:string)=>transport.stops.find(s=>s.id===id)?.name.replace(" bus stop","")||id;
  return <section className="bus-timetable" aria-label="Bus timetable">
    <h3>Bus services</h3>
    {routes.map(r=><div key={r.id}><strong>{r.id} · {r.name}</strong><p>{r.stops.map(name).join(" → ")}</p><small>Every {r.headway} min · departures from Residential {time(r.firstDeparture)}–{time(r.lastDeparture)} · {money(r.fareCents)}</small></div>)}
    <h3>Next departures here</h3>
    {departures.length?departures.map(d=><div key={`${d.bus}:${d.departureMinute}`}><strong>{d.route} · {time(d.departureMinute)}</strong><small>{d.seatsAvailable} seats · toward {d.remainingStops.map(name).join(" → ")}</small></div>):<p>No departures in the next 90 minutes.</p>}
  </section>;
}
