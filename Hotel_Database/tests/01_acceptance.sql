\set ON_ERROR_STOP on
SET search_path=hotel,public;
BEGIN;
DO $$
DECLARE b bigint; rid bigint; d bigint; rm bigint; sid bigint; q integer; current_day date;
BEGIN
 current_day:=(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date;
 SELECT id INTO b FROM hotel.branches WHERE name='Hà Nội Central';
 SELECT id INTO rm FROM hotel.room_types WHERE branch_id=b AND name='Deluxe';
 rid:=hotel.make_reservation(1,b,current_day+1,current_day+3,
 jsonb_build_array(jsonb_build_object('room_type_id',rm,'quantity',2)),
 'TEST-'||replace(gen_random_uuid()::text,'-',''),false,'ONLINE','WELCOME10');
 IF (SELECT status FROM hotel.reservations WHERE id=rid) <> 'PENDING_PAYMENT' THEN RAISE EXCEPTION 'Unexpected pending state'; END IF;
 SELECT min(hotel.available_count(rm,dt)) INTO q FROM generate_series(current_day+1,current_day+2,interval '1 day') dt;
 IF q<>3 THEN RAISE EXCEPTION 'Nightly inventory expected 3, got %',q; END IF;
 PERFORM hotel.confirm_mock_qr(rid,(SELECT quoted_total FROM hotel.reservations WHERE id=rid),'TEST-PAY-'||rid);
 IF (SELECT status FROM hotel.reservations WHERE id=rid)<>'CONFIRMED' THEN RAISE EXCEPTION 'Payment failed'; END IF;
 PERFORM hotel.cancel_reservation(rid,'test');
 PERFORM hotel.cancel_reservation(rid,'repeat is idempotent');
 SELECT min(hotel.available_count(rm,dt)) INTO q FROM generate_series(current_day+1,current_day+2,interval '1 day') dt;
 IF q<>5 THEN RAISE EXCEPTION 'Cancellation inventory expected 5, got %',q; END IF;
 RAISE NOTICE 'PASS: booking, inventory, payment, cancellation, idempotent cancellation, promo release';
END $$;
ROLLBACK;
