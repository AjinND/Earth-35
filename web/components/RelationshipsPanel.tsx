import {time} from "@/lib/api";
import type {RelationshipAppraisal,RelationshipEvidence,Resident,World} from "@/lib/types";
import styles from "./RelationshipsPanel.module.css";

const dimensions=["warmth","trust","respect","closeness","attraction","tension"] as const;
function when(minute:number) {return `Day ${Math.floor(minute/1440)+1}, ${time(minute)}`;}

function Appraisal({appraisal}:{appraisal:RelationshipAppraisal}) {
  const scores=dimensions.filter(key=>typeof appraisal[key]==="number");
  return <>
    <p className={styles.feeling}>{appraisal.feeling}</p>
    <p className={styles.authored}>{appraisal.reason}</p>
    {!!scores.length&&<dl className={styles.scores} aria-label="Resident's own assessments">
      {scores.map(key=><div key={key}><dt>{key}</dt><dd>{appraisal[key]}<span> / 100</span></dd></div>)}
    </dl>}
  </>;
}

function Evidence({items,names}:{items:RelationshipEvidence[];names:Map<string,string>}) {
  return <ol className={styles.evidence}>{[...items].sort((a,b)=>b.minute-a.minute).map(item=><li key={item.id}>
    <p>{item.description}</p>
    <small className="muted">{when(item.minute)} · {item.kind.replaceAll("_"," ")}{item.actor?` · ${names.get(item.actor)||item.actor}`:""}</small>
  </li>)}</ol>;
}

export default function RelationshipsPanel({resident,world}:{resident:Resident;world:World}) {
  const names=new Map(world.agents.map(agent=>[agent.id,agent.name]));
  names.set(resident.id,resident.name);
  for(const [id,bond] of Object.entries(resident.bonds??{}))if(bond.name&&!names.has(id))names.set(id,bond.name);
  const targets=[...new Set([...Object.keys(resident.relationships),...Object.keys(resident.bonds??{})])].filter(id=>id!==resident.id);
  return <section className={`surface ${styles.panel}`} aria-label="Relationships and feelings">
    <div className="section-heading"><h2>Relationships &amp; feelings</h2><span className="private-tag">PRIVATE</span></div>
    <p className="muted">{resident.name}’s own impressions, shaped by shared experiences. Each resident forms their own feelings.</p>
    {!targets.length?<p className="muted">No relationships or shared experiences recorded yet.</p>:<div className={styles.grid}>{targets.map(target=>{
      const bond=resident.bonds?.[target],appraisal=bond?.appraisal;
      const name=names.get(target)||bond?.name||target;
      const label=resident.relationships[target]||appraisal?.label;
      const history=[...(bond?.history??[])].sort((a,b)=>b.minute-a.minute);
      const availableEvidence=new Map([...history.flatMap(entry=>entry.evidence),...(bond?.evidence??[])].map(item=>[item.id,item]));
      const basis=(appraisal?.evidence??[]).flatMap(id=>availableEvidence.has(id)?[availableEvidence.get(id)!]:[]);
      return <article key={target} className={styles.card} aria-label={`${resident.name}’s view of ${name}`}>
        <div className={styles.heading}><h3>{name}</h3>{label&&<span className="pill">{label}</span>}</div>
        <small className="muted">{resident.name}’s perspective</small>
        {appraisal?<>
          <Appraisal appraisal={appraisal}/>
          {bond&&bond.appraisedMinute>=0&&<small className="muted">Resident-authored · {when(bond.appraisedMinute)}</small>}
          {!!basis.length&&<details><summary>What shaped this view</summary><Evidence items={basis} names={names}/></details>}
        </>:<p className="muted">No feelings recorded yet.</p>}
        {bond&&bond.interactionCount>0&&<p className={styles.meta}>{bond.interactionCount} recorded {bond.interactionCount===1?"interaction":"interactions"}{bond.lastInteractionMinute>=0?` · Last interaction ${when(bond.lastInteractionMinute)}`:""}</p>}
        {!!bond?.evidence?.length&&<details><summary>Recent interactions</summary><Evidence items={bond.evidence} names={names}/></details>}
        {!!history.length&&<details><summary>How feelings changed ({history.length})</summary><ol className={styles.history}>{history.map((entry,index)=><li key={`${entry.minute}-${index}`}>
          <div className={styles.heading}><strong>{when(entry.minute)}</strong>{entry.appraisal.label&&<span className="pill">{entry.appraisal.label}</span>}</div>
          <Appraisal appraisal={entry.appraisal}/>
          {!!entry.evidence.length&&<details><summary>Experiences behind this reflection</summary><Evidence items={entry.evidence} names={names}/></details>}
        </li>)}</ol></details>}
      </article>;
    })}</div>}
  </section>;
}
