"use client";

import Link from "next/link";
import type {Detail,World} from "@/lib/types";
import {money,time} from "@/lib/api";
import {constructionProgress,constructionStage} from "@/lib/construction";

const at=(minute:number)=>`Day ${Math.floor(minute/1440)+1}, ${time(minute)}`;
const purposeName=(purpose:string,world:World)=>{
  const target=purpose.slice(purpose.indexOf(":")+1);
  return world.innovations?.businesses.find(business=>business.id===purpose)?.name??world.buildings.find(building=>building.id===target)?.name??world.innovations?.businesses.find(business=>business.id===target)?.name??purpose.split(":")[0].replaceAll("_"," ");
};
export default function FinanceConstructionPanel({detail,world}:{detail:Detail;world:World}) {
  const resident=detail.state,finance=detail.livelihood?.financing;
  const sites=(detail.livelihood?.construction??world.construction)?.sites.filter(site=>site.sponsor===resident.id)??[];
  const objects=world.objects?.filter(object=>object.owner===resident.id)??[];
  if(!finance&&!sites.length&&!objects.length)return null;
  return <section className="surface finance-construction-panel" aria-label="Loans and world building"><h2>Loans &amp; world building</h2>
    {finance&&<><p className="muted">This resident chooses how to fund their business and construction. Loan installments are collected automatically from their balance over the agreed term.</p>
      <dl className="finance-totals"><div><dt>Outstanding debt</dt><dd>{money(finance.outstandingCents)}</dd></div><div><dt>Overdue payments</dt><dd className={finance.overdueCents>0?"error":""}>{money(finance.overdueCents)}</dd></div></dl>
      {!finance.loans.length?<p>No loans taken.</p>:<div className="innovation-grid">{finance.loans.map(loan=><article className="innovation-card" key={loan.id}><div className="innovation-heading"><h3>{purposeName(loan.purpose,world)}</h3><span className={`pill ${loan.overdueCents>0?"warning":""}`}>{loan.status}</span></div>
        <dl><div><dt>Amount borrowed</dt><dd>{money(loan.principalCents)}</dd></div><div><dt>Interest over term</dt><dd>{money(loan.interestCents)}</dd></div><div><dt>Total repayment</dt><dd>{money(loan.totalRepaymentCents)}</dd></div><div><dt>Repaid</dt><dd>{money(loan.paidCents)}</dd></div><div><dt>Remaining</dt><dd>{money(loan.outstandingCents)}</dd></div><div><dt>Installment</dt><dd>{money(loan.installmentCents)}</dd></div>{loan.overdueCents>0&&<div><dt>Overdue</dt><dd className="error">{money(loan.overdueCents)}</dd></div>}</dl>
        <p>{loan.termYears} years · {loan.installments} installments</p>{loan.outstandingCents>0&&loan.nextDueMinute!=null&&<p>Next due: {at(loan.nextDueMinute)}</p>}<p className="muted">Final due: {at(loan.maturityMinute)}</p>
      </article>)}</div>}
      {!!finance.offers.length&&<details><summary>Available loan terms</summary><p>Debt limit: {money(finance.borrowingLimitCents)}</p><ul>{finance.offers.map(offer=><li key={offer.termYears}>{offer.termYears} world years · {offer.installments} installments · {offer.annualSimpleInterestBasisPoints/100}% annual simple interest</li>)}</ul></details>}
    </>}
    <h3>Buildings sponsored</h3>{!sites.length?<p className="muted">No construction sponsored yet.</p>:<div className="innovation-grid">{sites.map(site=>{const progress=constructionProgress(site),workers=site.workers??[];return <article className="innovation-card" key={site.id}><div className="innovation-heading"><h3><Link href={`/?resident=${encodeURIComponent(site.id)}`}>{site.name}</Link></h3><span className="pill">{site.status.replaceAll("_"," ")}</span></div><p>{site.plannedType} · {site.laborMode==="self"?"Self build":"Paid resident workers"}</p>
      <progress max={100} value={progress.percent} aria-label={`${site.name} construction progress`}/><p>{progress.percent}% complete · {constructionStage(progress.fraction)} · {(progress.completed/60).toLocaleString("en-US",{maximumFractionDigits:1})} / {progress.required/60} labor hours</p>
      <p>{workers.length?`Building now: ${workers.map(worker=>worker.name).join(", ")}`:site.status==="completed"?"Construction completed":"No residents currently building"}</p><dl><div><dt>Available project budget</dt><dd>{money(site.unreservedBudgetCents)}</dd></div>{site.laborMode!=="self"&&<div><dt>Worker hourly pay</dt><dd>{money(site.hourlyWageCents)}</dd></div>}</dl>
    </article>;})}</div>}
    {!!objects.length&&<><h3>Signs placed</h3><ul>{objects.map(object=><li key={object.id}><Link href={`/?resident=${encodeURIComponent(object.id)}`}>{object.text}</Link> · {object.kind==="advertisement"?"Advertisement":"Signboard"} · {object.x.toFixed(1)}, {object.z.toFixed(1)}</li>)}</ul></>}
    {resident.pending&&["PLACE_OBJECT","MOVE_OBJECT"].includes(resident.pending.type)&&<p role="status">{resident.pending.type==="PLACE_OBJECT"?"Installing a sign":"Repositioning a sign"} · {Math.max(0,resident.pending.until-world.minute)} minutes of work remaining</p>}
  </section>;
}
