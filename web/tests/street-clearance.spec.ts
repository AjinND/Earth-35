import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {sidewalkQuads,signalPlacement} from "../lib/street-furniture";
import {organicStreets,streetDistance,organicPoint} from "../lib/organic-layout";
import {organicCityLayout} from "../lib/city-layout";
import type {World} from "../lib/types";

const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
test("sidewalk surfaces stop clear of every curved carriageway, including intersection interiors",()=>{
  const quads=organicStreets.flatMap(points=>[-2.4,2.4].flatMap(offset=>sidewalkQuads(points,offset)));
  expect(quads.length).toBeGreaterThan(10000);
  expect(quads.every(quad=>{
    for(let u=0;u<=1;u+=.5)for(let v=0;v<=1;v+=.5) {
      const x=(1-u)*((1-v)*quad[0].x+v*quad[1].x)+u*((1-v)*quad[2].x+v*quad[3].x);
      const z=(1-u)*((1-v)*quad[0].z+v*quad[1].z)+u*((1-v)*quad[2].z+v*quad[3].z);
      if(streetDistance(x,z)<=1.6)return false;
    }
    return true;
  })).toBe(true);
  const crossing=organicPoint(-342,-192);
  expect(quads.some(quad=>quad.some(p=>Math.hypot(p.x-crossing.x,p.z-crossing.z)<2))).toBe(false);
});
test("all eight signal poles and housings stay outside road lanes while crossings follow the curved street",()=>{
  expect(world.transport!.signals.length).toBe(8);
  for(const signal of world.transport!.signals) {
    const p=signalPlacement(signal,organicCityLayout);
    expect(streetDistance(p.pole.x,p.pole.z)).toBeGreaterThan(2.1);
    for(let x=-.22;x<=.22;x+=.11)for(let z=-.22;z<=.22;z+=.11)expect(streetDistance(p.pole.x+x,p.pole.z+z)).toBeGreaterThan(1.6);
    expect(streetDistance(p.crossing.x,p.crossing.z)).toBeLessThan(.05);
  }
});
test("corrected intersections render with curbside signals on the deployed city",async({page},info)=>{
  test.setTimeout(60000);await page.setViewportSize({width:1536,height:960});
  const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));
  await page.goto("/");const canvas=page.locator(".world-stage canvas");
  await expect(canvas).toHaveAttribute("data-sidewalk-junctions","clipped",{timeout:30000});
  await expect(canvas).toHaveAttribute("data-signal-poles-off-road","true");
  await page.getByRole("button",{name:"Browse places",exact:true}).click();await page.getByRole("button",{name:"All places",exact:true}).click();
  await page.getByRole("button",{name:"Residential bus stop",exact:true}).click();await expect(page.getByLabel("Map zoom")).toHaveText("1200%");
  await page.locator(".world-stage").screenshot({path:info.outputPath("curved-junction-clearance.png")});
  expect(errors).toEqual([]);
});
