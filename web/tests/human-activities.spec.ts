import {expect,test} from "@playwright/test";
import {avatarPerformance,performanceHeading} from "../lib/avatar";
import {ResidentPlayback} from "../lib/resident-playback";
import {PlaybackTimeline} from "../lib/playback-timeline";
import type {Agent} from "../lib/types";
import type {Building} from "../lib/types";
import {workspaceSeats} from "../lib/gathering-furniture";

type Performance=NonNullable<Agent["performance"]>;
const performance=(patch:Partial<Performance>={}):Performance=>({actionId:"accepted-task",action:"WORK",phase:"perform",posture:"standing",gesture:"work",heldItem:null,task:"Working at a station",heading:1.3,anchored:true,activeMinutes:6,...patch});

test("physical activity families use the recorded task and real held object",()=>{
  for(const [action,gesture,motion,item] of [
    ["JUMP","jump","Jump",null],
    ["WORK","work","Work",null],["BUILD_CONSTRUCTION","build","Build","construction-tool"],
    ["BUILD_PROJECT","work","Assemble",null],["PRODUCE_PRODUCT","produce","Produce",null],
    ["PLACE_OBJECT","place","Install",null],["MOVE_OBJECT","place","Install",null],
    ["READ_BOOK","read","Read","book"],["RESEARCH_PROJECT","research","Research",null],
    ["DO_ACTIVITY","observe","Observe",null],["TALK","talk","Talk",null],
    ["CHAT","listen","Listen",null],["EAT","eat","Eat","meal"],["EAT","drink","Drink","coffee"],
  ]) {
    const result=avatarPerformance(action!,null,performance({action:action!,gesture:gesture!,heldItem:item}));
    expect(result.motion,action!).toBe(motion);expect(result.heldItem).toBe(item);expect(result.anchored).toBe(true);
  }
});

test("approaching, blocked and paused workers cannot look productive or invent footsteps",()=>{
  for(const phase of ["approach","blocked","paused"]) {
    const state=performance({action:"BUILD_CONSTRUCTION",gesture:"build",phase,heldItem:"construction-tool"});
    expect(avatarPerformance("BUILD_CONSTRUCTION",null,state,false).motion).toBe("Idle");
    expect(avatarPerformance("BUILD_CONSTRUCTION",null,state,true).motion).toBe("Walk");
  }
  expect(avatarPerformance("RUN",null,undefined,false).motion).toBe("Idle");
  expect(avatarPerformance("RUN",null,undefined,true).motion).toBe("Run");
});

test("posture uses real seating and lying state instead of a floating chair",()=>{
  expect(avatarPerformance("REST",null,performance({action:"REST",gesture:"idle",posture:"standing"}))).toMatchObject({motion:"Idle",posture:"standing"});
  expect(avatarPerformance("REST",null,performance({action:"REST",gesture:"idle",posture:"seated"}))).toMatchObject({motion:"Rest",posture:"seated"});
  expect(avatarPerformance("SLEEP",null,performance({action:"SLEEP",gesture:"idle",posture:"lying"}))).toMatchObject({motion:"Sleep",posture:"lying"});
  expect(avatarPerformance("REST",null,undefined,false,false).posture).toBe("standing");
});

test("the action label cannot conjure props or overrule the authoritative phase",()=>{
  expect(avatarPerformance("READ_BOOK",null,performance({gesture:"read",heldItem:null})).heldItem).toBeNull();
  expect(avatarPerformance("BUILD_CONSTRUCTION",null,performance({gesture:"observe",heldItem:null}))).toMatchObject({motion:"Observe",heldItem:null});
  expect(avatarPerformance("WORK",null,performance({gesture:"unknown"})).motion).toBe("Idle");
  expect(avatarPerformance("BUILD_CONSTRUCTION",null,undefined,false,false,false).motion).toBe("Idle");
});

test("a stationary task faces its actual work target while movement follows its path",()=>{
  expect(performanceHeading(performance(),false)).toBe(1.3);
  expect(performanceHeading(performance(),true)).toBeUndefined();
  expect(performanceHeading(performance({heading:NaN}),false)).toBeUndefined();
});

test("task phases and anchoring follow buffered authoritative positions",()=>{
  const playback=new ResidentPlayback(new PlaybackTimeline(0));
  const approach=performance({phase:"approach",activeMinutes:0});
  const working=performance({phase:"perform",activeMinutes:1});
  playback.ingest(1,{x:0,z:0,activity:"WORK",performance:approach},1000,0);
  playback.ingest(2,{x:2,z:0,activity:"WORK",motion:[{x:0,z:0,heading:Math.PI/2},{x:2,z:0,heading:Math.PI/2}],performance:approach},1000,1000);
  const moving=playback.sample(1500);
  expect(moving.moving).toBe(true);expect(moving.performance?.phase).toBe("approach");expect(moving.routeAnchored).toBe(true);
  playback.ingest(3,{x:2,z:0,activity:"WORK",performance:working},1000,2000);
  const atStation=playback.sample(3000);
  expect(atStation).toMatchObject({x:2,z:0,moving:false,routeAnchored:true});expect(atStation.performance).toEqual(working);
});

test("workplace seating requires a real desk stance and actual seated arrival",()=>{
  const building={id:"office",type:"office",x:10,z:20,workspaces:[{id:"office:desk",kind:"desk",x:9,z:20,heading:Math.PI},{id:"office:counter",kind:"counter",x:11,z:20,heading:0}]} as Building;
  const worker={id:"worker",x:9,z:20,location:"office",performance:performance({posture:"seated"})} as Agent;
  expect(workspaceSeats([worker],[building]).get(worker.id)).toEqual({x:9,z:20,y:.83,rotation:Math.PI});
  expect(workspaceSeats([{...worker,x:9.2}],[building]).size).toBe(0);
  expect(workspaceSeats([{...worker,performance:performance({posture:"seated",phase:"approach"})}],[building]).size).toBe(0);
  expect(workspaceSeats([{...worker,x:11}],[building]).size).toBe(0);
  expect(workspaceSeats([{...worker,location:"road"}],[building]).size).toBe(0);
  expect(workspaceSeats([{...worker,performance:performance({posture:"standing"})}],[building]).size).toBe(0);
  expect(workspaceSeats([{...worker,performance:performance({posture:"seated",phase:"paused"})}],[building]).get(worker.id)?.y).toBe(.83);
});
