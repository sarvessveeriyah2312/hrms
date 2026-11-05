-- Claim workflows match on the claim amount, which has cents; leave workflows keep matching on days.
alter table approval_flow alter column min_quantity type numeric(12,2);
alter table approval_flow alter column max_quantity type numeric(12,2);
