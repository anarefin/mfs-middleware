package mfs.mw.app.gateway.service;

import java.util.UUID;
import mfs.mw.app.gateway.dto.Favourite;
import mfs.mw.app.gateway.dto.FavouriteList;
import mfs.mw.app.gateway.dto.SavedBiller;
import mfs.mw.app.gateway.dto.SavedBillerList;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.repository.FavouriteRepository;
import mfs.mw.app.gateway.repository.SavedBillerRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Saved billers and favourites of one wallet (ticket 107a). Every call is scoped to that wallet. */
@Service
public class PreferenceService {

  private final SavedBillerRepository billers;
  private final FavouriteRepository favourites;

  public PreferenceService(SavedBillerRepository billers, FavouriteRepository favourites) {
    this.billers = billers;
    this.favourites = favourites;
  }

  public SavedBillerList billers(String walletId) {
    return new SavedBillerList(billers.list(walletId));
  }

  public SavedBiller saveBiller(String walletId, String billerCode, String accountNo, String nickname) {
    try {
      return billers.insert(walletId, billerCode, accountNo, nickname);
    } catch (DuplicateKeyException e) {
      throw new MwException(ErrorCodes.IDEM_CONFLICT, "Already saved");
    }
  }

  public void deleteBiller(String walletId, String id) {
    if (!billers.delete(walletId, uuid(id))) {
      throw missing();
    }
  }

  public FavouriteList favourites(String walletId) {
    return new FavouriteList(favourites.list(walletId));
  }

  public Favourite saveFavourite(String walletId, String msisdn, String label) {
    try {
      return favourites.insert(walletId, msisdn, label);
    } catch (DuplicateKeyException e) {
      throw new MwException(ErrorCodes.IDEM_CONFLICT, "Already saved");
    }
  }

  public void deleteFavourite(String walletId, String id) {
    if (!favourites.delete(walletId, uuid(id))) {
      throw missing();
    }
  }

  private static UUID uuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Not an id");
    }
  }

  private static MwException missing() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
  }
}
