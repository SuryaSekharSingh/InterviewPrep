package com.interviewedge.identity;

public interface IdentityVerifier {
  record Identity(String uid, boolean admin, long authenticatedAt) {}

  Identity verify(String token);

  void delete(String uid);
}
