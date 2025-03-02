package io.hyperfoil.tools.horreum.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Function;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;
import javax.persistence.EntityManager;

import io.hyperfoil.tools.horreum.svc.Util;
import io.quarkus.security.identity.SecurityIdentity;

@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 100)
@WithRoles
public class RolesInterceptor {
   private static final ThreadLocal<SecurityIdentity> currentIdentity = new ThreadLocal<>();

   @Inject
   RoleManager roleManager;

   @Inject
   SecurityIdentity identity;

   @Inject
   EntityManager em;

   public static void setCurrentIdentity(SecurityIdentity identity) {
      currentIdentity.set(identity);
   }

   @AroundInvoke
   public Object intercept(InvocationContext ctx) throws Exception {
      SecurityIdentity identity = currentIdentity.get();
      if (identity == null) {
         identity = this.identity;
      }
      WithRoles annotation = Util.getAnnotation(ctx.getMethod(), WithRoles.class);
      boolean hasParams = annotation.fromParams() != WithRoles.IgnoreParams.class;
      if (identity.isAnonymous() && annotation.extras().length == 0 && !hasParams) {
         return ctx.proceed();
      }
      Collection<String> roles = identity.getRoles();
      if (annotation.extras().length != 0 || annotation.addUsername() || hasParams) {
         roles = new ArrayList<>(roles);
         Collections.addAll(roles, annotation.extras());
         if (annotation.addUsername()) {
            roles.add(identity.getPrincipal().getName());
         }
         if (hasParams) {
            Function<Object[], String[]> fromParams = annotation.fromParams().getConstructor().newInstance();
            Collections.addAll(roles, fromParams.apply(ctx.getParameters()));
         }
      }
      String previousRoles = roleManager.setRoles(em, roles);
      Throwable t1 = null;
      try {
         return ctx.proceed();
      } catch (Throwable t) {
         t1 = t;
         throw t;
      } finally {
         try {
            roleManager.setRoles(em, previousRoles);
         } catch (Throwable t2) {
            if (t1 != null) {
               t2.addSuppressed(t1);
            }
            //noinspection ThrowFromFinallyBlock
            throw t2;
         }
      }
   }
}
