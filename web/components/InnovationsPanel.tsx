"use client";

import Link from "next/link";
import {money,time} from "@/lib/api";
import type {InnovationsView,Resident,World,ProjectView,ProductView,BusinessView,ObservedProblem} from "@/lib/types";

const recordedAt=(minute:number)=>`Day ${Math.floor(minute/1440)+1}, ${time(minute)}`;
const placeLink=(id:string)=>`/?resident=${encodeURIComponent(id)}`;
const placeName=(world:World,id:string)=>world.buildings.find(place=>place.id===id)?.name??id;
const residentName=(world:World,id:string)=>world.agents.find(resident=>resident.id===id)?.name??id;
const evidenceLabels:Record<string,string>={hunger:"Hunger",energy:"Energy",wetness:"Wetness",residents:"Residents",hungryResidents:"Residents with high hunger",lowEnergyResidents:"Residents with low energy",wetResidents:"Residents with high wetness",mealStock:"Meals in store stock",overdueBooks:"Overdue books",longestOverdueMinutes:"Longest overdue time",rainNow:"Raining",temperatureC:"Temperature"};
const evidenceValue=(key:string,value:number)=>["hunger","energy","wetness"].includes(key)?`${value} / 100`:key==="temperatureC"?`${value}°C`:key==="rainNow"?(value?"Yes":"No"):key==="longestOverdueMinutes"?`${value} minutes`:String(value);

function ObservationList({observations}:{observations:ObservedProblem[]}) {
  return <ul className="innovation-grid innovation-observations">{observations.map(observation=><li className="innovation-card" key={observation.id}>
    <p>{observation.description}</p><dl>{Object.entries(observation.evidence).map(([key,value])=><div key={key}>
      <dt>{evidenceLabels[key]??key.replace(/([a-z])([A-Z])/g,"$1 $2").replaceAll("_"," ")}</dt><dd>{evidenceValue(key,value)}</dd>
    </div>)}</dl>
  </li>)}</ul>;
}

export function ProjectCard({project,world,privateView=false}:{project:ProjectView;world:World;privateView?:boolean}) {
  return <article className="innovation-card"><div className="innovation-heading"><h3>{project.title}</h3><span className="pill">{project.status.replaceAll("_"," ")}</span></div>
    <p className="muted">{project.subject} · <Link href={placeLink(project.location)}>{placeName(world,project.location)}</Link></p>
    <p><strong>Problem:</strong> {project.problem}</p><details><summary>Design and learned concepts</summary><p className="authored-work">{project.description}</p>{!!project.concepts?.length&&<ul>{project.concepts.map(concept=><li key={concept}>{concept}</li>)}</ul>}</details>
    <p><strong>Collaborators:</strong> {project.members.map(id=>residentName(world,id)).join(", ")}</p>
    {privateView&&<><p className="muted">Business consent: {project.businessEndorsements?.length??0}/{project.members.length} members endorsed co-ownership</p>
      <details><summary>Research ({project.research.length} findings)</summary>{!project.research.length?<p>No completed research yet.</p>:project.research.map(finding=><div className="innovation-finding" key={finding.id}><strong>{finding.method}</strong><p>{finding.result}</p><p className="muted">Learned: {finding.concept}</p>{!!Object.keys(finding.evidence??{}).length&&<dl>{Object.entries(finding.evidence??{}).map(([key,value])=><div key={key}><dt>{key.replaceAll("_"," ")}</dt><dd>{value}</dd></div>)}</dl>}<small>{recordedAt(finding.minute)}</small></div>)}</details></>}
    <small>Started {recordedAt(project.createdMinute)}</small>
  </article>;
}

export function ProductCard({product,owned}:{product:ProductView;owned?:number}) {
  return <article className="innovation-card"><div className="innovation-heading"><h3>{product.title}</h3>{owned!==undefined&&<span className="pill">{owned} owned units</span>}</div><p>{product.description}</p><p><strong>World effect:</strong> {product.effects}</p><p className="muted">{product.subject} · Materials per unit: {money(product.unitCost)}</p></article>;
}

export function BusinessCard({business,world,privateView=false}:{business:BusinessView;world:World;privateView?:boolean}) {
  const kind=business.kind==="bakery"?"Bakery":business.kind==="fruit-shop"?"Fruit shop":business.kind==="startup"?"Startup":null;
  return <article className="innovation-card"><h3>{business.name}</h3>{kind&&<p className="muted">{kind}</p>}<p><Link href={placeLink(business.location)}>{placeName(world,business.location)}</Link> · <span>{business.stock} units in stock</span></p>
    <p><strong>Price:</strong> {money(business.priceCents)} · {business.sales} units sold</p><p><strong>Co-owners:</strong> {business.owners.map(id=>residentName(world,id)).join(", ")}</p>
    {privateView&&business.balanceCents!=null&&<dl><div><dt>Business funds</dt><dd>{money(business.balanceCents)}</dd></div>{business.revenueCents!=null&&<div><dt>Sales revenue</dt><dd>{money(business.revenueCents)}</dd></div>}{business.contributedCents!=null&&<div><dt>Owner capital contributed</dt><dd>{money(business.contributedCents)}</dd></div>}{business.availableProfitCents!=null&&<div><dt>Available profit</dt><dd>{money(business.availableProfitCents)}</dd></div>}</dl>}
    <p className="muted">{business.employeeSeats===0?<>Run by {business.owners.length===1?"the owner":"its owners"}. Materials are paid from business funds.</>:<>Job listing: {business.jobId}{business.employeeSeats!=null?` · ${business.employeeSeats} employee seat${business.employeeSeats===1?"":"s"}`:""}. Wages and materials are paid from business funds.</>}</p>
  </article>;
}

export default function InnovationsPanel({innovations,resident,world}:{innovations?:InnovationsView;resident:Resident;world:World}) {
  if(!innovations)return null;
  const projects=innovations.projects.filter(project=>project.members.includes(resident.id));
  const businesses=innovations.businesses.filter(business=>business.owners.includes(resident.id));
  const products=innovations.products.filter(product=>(resident.inventory[product.id]??0)>0||projects.some(project=>project.id===product.project)||businesses.some(business=>business.productId===product.id));
  const active=resident.pending&&["RESEARCH_PROJECT","BUILD_PROJECT","PRODUCE_PRODUCT"].includes(resident.pending.type)?resident.pending:null;
  const activity=active?.type==="RESEARCH_PROJECT"?"Researching":active?.type==="BUILD_PROJECT"?"Building a prototype":"Manufacturing";
  return <section className="surface innovations-panel" aria-label="Projects and businesses"><h2>Projects &amp; businesses</h2>
    <p className="muted">This resident identifies useful problems, researches them, chooses collaborators and builds usable products in New Eden. Owners observe their decisions here.</p>
    {active&&<p role="status">{activity} · {projects.find(project=>project.id===active.target)?.title??businesses.find(business=>business.id===active.target)?.name??active.target} · {Math.max(0,active.until-world.minute)} minutes remaining</p>}
    <h3>Declared interests</h3><p>{innovations.interests.length?innovations.interests.join(", "):"No interests published yet."}</p>
    {!!innovations.interestedResidents.length&&<details><summary>Residents with shared interests</summary><ul>{innovations.interestedResidents.map(peer=><li key={peer.id}>{peer.name} · {peer.interests.join(", ")}</li>)}</ul></details>}
    <section aria-label="Current personal problems"><h3>Current personal problems</h3>{!innovations.problems.length?<p className="muted">No current personal problems detected.</p>:<ObservationList observations={innovations.problems}/>}</section>
    {!!innovations.sharedConditions?.length&&<section aria-label="Shared city conditions"><h3>Shared city conditions</h3><p className="muted">Current city facts available to every resident. Needs and available supplies do not establish demand.</p><ObservationList observations={innovations.sharedConditions}/></section>}
    <h3>Project invitations</h3>{!innovations.invitations.length?<p className="muted">No project invitations.</p>:<div className="innovation-grid">{innovations.invitations.map(invitation=><article className="innovation-card" key={invitation.id}><div className="innovation-heading"><h4>{innovations.projects.find(project=>project.id===invitation.project)?.title??invitation.project}</h4><span className="pill">{invitation.status}</span></div><p>{residentName(world,invitation.inviter)} invited {residentName(world,invitation.invited)}</p><p className="authored-work">{invitation.message}</p><small>{recordedAt(invitation.minute)} · Each resident decides independently.</small></article>)}</div>}
    <h3>Current projects</h3>{!projects.length?<p className="muted">No projects started or joined. A learned design can become a project at an open workplace.</p>:<div className="innovation-grid">{projects.map(project=><ProjectCard key={project.id} project={project} world={world} privateView/>)}</div>}
    <h3>Products</h3>{!products.length?<p className="muted">No products owned or offered yet. Stock requires completed work and paid materials.</p>:<div className="innovation-grid">{products.map(product=><ProductCard key={product.id} product={product} owned={resident.inventory[product.id]??0}/>)}</div>}
    <h3>Resident businesses</h3>{!businesses.length?<p className="muted">No businesses yet. Residents may open a funded bakery or fruit shop, or launch a startup from a tested product with member consent.</p>:<div className="innovation-grid">{businesses.map(business=><BusinessCard key={business.id} business={business} world={world} privateView/>)}</div>}
    {!!products.length&&<p className="muted">Product use consumes one owned unit. Preparing stock takes work and materials; a listed product can have zero units available.</p>}
    <Link className="text-link" href="/innovations">Explore community projects and products</Link>
  </section>;
}
