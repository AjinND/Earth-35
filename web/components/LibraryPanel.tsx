"use client";

import {useId,useState} from "react";
import {time} from "@/lib/api";
import type {LibraryView,SubjectKnowledge,Resident} from "@/lib/types";

const depths=["introductory","intermediate","advanced"];
const recordedAt=(minute:number)=>`Day ${Math.floor(minute/1440)+1}, ${time(minute)}`;

export default function LibraryPanel({library,knowledge={},pending,minute}:{library?:LibraryView;knowledge?:Record<string,SubjectKnowledge>;pending:Resident["pending"];minute:number}) {
  const [query,setQuery]=useState("");
  const [subjectFilter,setSubjectFilter]=useState("");
  const [depthFilter,setDepthFilter]=useState("");
  const filterId=useId();
  if(!library)return null;
  const reading=pending?.type==="READ_BOOK"?library.catalog.find(book=>book.id===pending.target):null;
  const subjects=Object.entries(knowledge).sort((a,b)=>(b[1].points??0)-(a[1].points??0)||b[1].studyMinutes-a[1].studyMinutes);
  const catalogSubjects=[...new Set(library.catalog.map(book=>book.subject))].sort();
  const search=query.trim().toLocaleLowerCase();
  const books=library.catalog.filter(book=>(!subjectFilter||book.subject===subjectFilter)&&(!depthFilter||(book.depth??"introductory")===depthFilter)&&
    (!search||`${book.title} ${book.subject} ${book.description}`.toLocaleLowerCase().includes(search)));
  const reasoning=library.reasoning??[];
  const creations=library.creations??[];
  return <section className="surface library-panel" aria-label="Books and knowledge">
    <h2>Books &amp; knowledge</h2>
    <p className="muted">This resident chooses what to read, which problems to explore and what to create. Each learned lesson builds subject knowledge; deeper study enables reasoning and original work.</p>
    <p className="library-overview">{library.catalog.length} books · {catalogSubjects.length} subjects · Introductory to advanced</p>
    {reading&&<p role="status">Reading <strong>{reading.title}</strong> · {Math.max(0,(pending?.until??minute)-minute)} minutes remaining</p>}
    <h3>Borrowed books <small>({library.loans.length}/{library.loanLimit})</small></h3>
    <p className="muted">Free {library.loanDays}-day loans. Books must be returned at the library; overdue loans prevent further borrowing.</p>
    {!library.loans.length?<p>No books borrowed.</p>:<div className="action-list">{library.loans.map(loan=>{
      const remaining=loan.dueMinute-minute,overdue=remaining<=0;
      return <article key={loan.book}><strong>{loan.title}</strong><span className={`pill${overdue?" error":""}`}>{overdue?"Overdue":remaining<=1440?"Due soon":"Borrowed"}</span>
        <p>{loan.subject} · Due day {Math.floor(loan.dueMinute/1440)+1}, {time(loan.dueMinute)}</p>
        {overdue&&<p className="error">Return this book to the library before borrowing again.</p>}
      </article>;
    })}</div>}
    <h3>Subject knowledge</h3>
    <p className="muted">Reasoning requires 12 knowledge points and four concepts. Creation requires 28 points, four intermediate or advanced concepts and a recorded reasoning exercise.</p>
    {!subjects.length?<p className="muted">No subjects studied yet. Visiting the library or borrowing a book does not add knowledge.</p>:<div className="knowledge-list">{subjects.map(([subject,study])=>{
      const points=study.points??0,maximum=study.maximumPoints??56,understanding=Math.max(0,Math.min(100,study.understanding??points/maximum*100));
      return <article key={subject}><div className="knowledge-heading"><strong>{subject}</strong><span className="pill">{study.level}</span></div>
        <progress aria-label={`${subject} understanding`} max={100} value={understanding}/>
        <p className="knowledge-score">{points}/{maximum} knowledge points · {Math.round(understanding)}% of subject curriculum</p>
        <p>{study.readingMinutes} minutes read · {study.concepts.length} concepts learned</p>
        {!!study.researchPoints&&<p className="muted">{study.researchPoints} points earned through completed project research</p>}
        <p className="muted">Deepest study: {study.depth??"introductory"} · {(study.intermediateConcepts??0)+(study.advancedConcepts??0)} deeper concepts</p>
        <div className="knowledge-capabilities"><span className={`pill${study.canReason?" ready":""}`}>{study.canReason?"Reasoning available":"Building toward reasoning"}</span><span className={`pill${study.canCreate?" ready":""}`}>{study.canCreate?"Creation knowledge ready":"Building toward creation"}</span></div>
        {!!study.concepts.length&&<details><summary>Learned concepts</summary><ul>{study.concepts.map(concept=><li key={concept}>{concept}</li>)}</ul></details>}
      </article>;
    })}</div>}
    <h3>Reasoning <small>({reasoning.length} recent)</small></h3>
    {!reasoning.length?<p className="muted">No reasoning exercises recorded yet. After sufficient study, this resident can apply learned concepts to a specific problem.</p>:<div className="library-artifacts">{reasoning.map(thought=><details id={`${filterId}-reasoning-${thought.id}`} key={thought.id}>
      <summary>{thought.problem}</summary><p className="muted">{thought.subject} · {recordedAt(thought.minute)} · {thought.id}</p>
      <p className="authored-work">{thought.analysis}</p><h4>Concepts applied</h4><ul>{thought.concepts.map(concept=><li key={concept}>{concept}</li>)}</ul>
    </details>)}</div>}
    <h3>Created works <small>({creations.length} recent)</small></h3>
    <p className="muted">Resident-authored designs and proposals. Residents can develop compatible work into researched projects, usable products and businesses; follow their progress in Projects &amp; businesses.</p>
    {!creations.length?<p className="muted">No works created yet. Deeper knowledge and a reasoning exercise enable products, business proposals, research, art, writing and design.</p>:<div className="library-artifacts">{creations.map(work=><details key={work.id}>
      <summary>{work.title} <span className="pill">{work.kind}</span></summary><p className="muted">{work.subject} · {recordedAt(work.minute)}</p>
      <p className="authored-work">{work.content}</p>
      <p>{reasoning.some(thought=>thought.id===work.reasoningId)?<a href={`#${filterId}-reasoning-${work.reasoningId}`} onClick={()=>{const source=document.getElementById(`${filterId}-reasoning-${work.reasoningId}`);if(source instanceof HTMLDetailsElement)source.open=true;}}>Based on reasoning {work.reasoningId}</a>:`Based on recorded reasoning ${work.reasoningId}`}</p>
      <h4>Knowledge behind this work</h4><ul>{work.concepts.map(concept=><li key={concept}>{concept}</li>)}</ul>
    </details>)}</div>}
    <details className="library-catalog"><summary>Library collection ({library.catalog.length} {library.catalog.length===1?"book":"books"})</summary>
      <div className="library-filters">
        <label htmlFor={`${filterId}-search`}>Search books<input id={`${filterId}-search`} type="search" value={query} onChange={event=>setQuery(event.target.value)} placeholder="Title, subject or description"/></label>
        <label htmlFor={`${filterId}-subject`}>Subject<select id={`${filterId}-subject`} value={subjectFilter} onChange={event=>setSubjectFilter(event.target.value)}><option value="">All subjects</option>{catalogSubjects.map(subject=><option key={subject} value={subject}>{subject}</option>)}</select></label>
        <label htmlFor={`${filterId}-depth`}>Depth<select id={`${filterId}-depth`} value={depthFilter} onChange={event=>setDepthFilter(event.target.value)}><option value="">All depths</option>{depths.map(depth=><option key={depth} value={depth}>{depth}</option>)}</select></label>
      </div>
      <p className="muted" role="status">Showing {books.length} of {library.catalog.length} books</p>
      {!books.length?<p>No books match these filters.</p>:<div className="action-list">{books.map(book=>{
        const learned=Math.min(book.lessonCount??4,Math.floor(book.studyMinutes/(book.lessonMinutes??30)));
        return <article key={book.id}><div className="knowledge-heading"><strong>{book.title}</strong><span className="pill">{book.depth??"introductory"}</span></div><p>{book.description}</p>
          <small>{book.subject} · {book.availableCopies}/{book.copies} copies available · {book.studyMinutes}/{book.masteryMinutes} minutes studied</small>
          <small>{learned}/{book.lessonCount??4} lessons learned · {book.lessonMinutes??30} minutes per lesson</small>
          <p className={book.unlocked===false?"muted":""}>{book.unlocked===false?`Requires ${book.prerequisitePoints} knowledge points in ${book.subject}`:learned===(book.lessonCount??4)?"All lessons learned; available to reread":"Ready to study"}</p>
        </article>;
      })}</div>}
    </details>
  </section>;
}
