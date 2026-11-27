package com.Hotel.Module_CheckInCheckOut.service;

import com.Hotel.Module_CheckInCheckOut.entity.Invoice;
import com.Hotel.Module_CheckInCheckOut.entity.Receptionist;
import com.Hotel.Module_CheckInCheckOut.repository.StayRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class InvoiceService {
    private final StayRepository repo;
    public InvoiceService(StayRepository repo) { this.repo=repo; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Invoice get(long id, Receptionist actor) {
        var r=repo.reservation(id,false); actor.authorize(r.branchId());
        return repo.invoice(id);
    }
}
