\set ON_ERROR_STOP on
SET search_path=hotel,public;
BEGIN;
DO $$
DECLARE bid bigint; rt bigint; d date;
BEGIN
 SELECT id INTO bid FROM hotel.branches WHERE name='Hà Nội Central';
 SELECT id INTO rt FROM hotel.room_types WHERE branch_id=bid AND name='Deluxe';
 d:=(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+10;
 BEGIN
  PERFORM hotel.make_reservation(1,bid,d,d+2,jsonb_build_array(jsonb_build_object('room_type_id',rt,'quantity',6)),'TOO-MANY',false);
  RAISE EXCEPTION 'FAILED: expected capacity exception';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM='FAILED: expected capacity exception' THEN RAISE; END IF;
 END;
 BEGIN
  PERFORM hotel.make_reservation(1,bid,d,d,jsonb_build_array(jsonb_build_object('room_type_id',rt,'quantity',1)),'BAD-DATE',false);
  RAISE EXCEPTION 'FAILED: expected invalid date';
 EXCEPTION WHEN OTHERS THEN
  IF SQLERRM='FAILED: expected invalid date' THEN RAISE; END IF;
 END;
 RAISE NOTICE 'PASS: invalid date and overbooking correctly rejected';
END $$;
ROLLBACK;
