%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
%   Prolog representation of a data flow policy
%   
%   Source: allowall
% 	
%	Do not edit this file, it has been generated automatically
% 	by XText/Xtend.
%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
%
% Only required for SWI-Prolog
% Allow the following predicates to be scattered around the prolog file.
% Otherwise Prolog will issue a warning if they are not stated in subsequent lines.		
%:- discontiguous service/1.
%:- discontiguous rule/1.
%:- discontiguous has_capability/2.
%:- discontiguous has_property/3.
%:- discontiguous has_target/2.
%:- discontiguous requires_prerequisites/2.
%:- discontiguous has_alternativedecision/2.
%:- discontiguous has_obligation/2.
%:- discontiguous receives_label/2.
regex(A,B,C) :- class("java.util.regex.Pattern") <- matches(A,B) returns C.		
%%%%%%%% Rules %%%%%%%%%%%%
rule(allowall).
has_target(allowall, service1542799566).
service(service1542799566).
has_endpoint(service1542799566,".*").
receives_label(allowall) :- label(_).
has_decision(allowall, allow).

%%%%% Services %%%%%%%%%%%%

