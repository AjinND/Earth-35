"use client";

import {useState} from "react";
import type {World} from "@/lib/types";
import {ProjectCard,ProductCard,BusinessCard} from "./InnovationsPanel";

export default function CommunityInnovations({world}:{world:World}) {
  const [query,setQuery]=useState("");
  const innovations=world.innovations;
  const search=query.trim().toLocaleLowerCase();
  const projects=(innovations?.projects??[]).filter(project=>!search||`${project.title} ${project.subject} ${project.problem}`.toLocaleLowerCase().includes(search));
  const products=(innovations?.products??[]).filter(product=>!search||`${product.title} ${product.subject} ${product.description}`.toLocaleLowerCase().includes(search));
  const businesses=(innovations?.businesses??[]).filter(business=>!search||`${business.name} ${business.location}`.toLocaleLowerCase().includes(search));
  return <div className="page-content community-innovations"><div className="eyebrow">MADE IN NEW EDEN</div><h1>Ideas taking shape.</h1><p className="lede">Discover what residents are researching, building and selling in the shared world.</p>
    <label className="innovation-search">Search community work<input type="search" value={query} onChange={event=>setQuery(event.target.value)} placeholder="Project, subject, product or business"/></label>
    <section aria-label="Community projects"><h2>Projects</h2>{!projects.length?<p className="muted">{search?"No matching projects.":"Residents have not started any public projects yet."}</p>:<div className="innovation-grid">{projects.map(project=><ProjectCard key={project.id} project={project} world={world}/>)}</div>}</section>
    <section aria-label="Community products"><h2>Products in the world</h2>{!products.length?<p className="muted">{search?"No matching products.":"Products appear when residents open a small business or build a prototype. Available units require completed work."}</p>:<div className="innovation-grid">{products.map(product=><ProductCard key={product.id} product={product}/>)}</div>}</section>
    <section aria-label="Community businesses"><h2>Resident businesses</h2>{!businesses.length?<p className="muted">{search?"No matching businesses.":"Residents may choose to open funded small shops or launch startups from tested products with co-owner consent."}</p>:<div className="innovation-grid">{businesses.map(business=><BusinessCard key={business.id} business={business} world={world}/>)}</div>}</section>
  </div>;
}
