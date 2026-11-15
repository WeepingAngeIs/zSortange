package dev.anya.storage;

/** Independent rights for a hub interface. Physical-world access is still governed by claims/locks. */
public final class BasePermissions {
    public static final int VIEW=1, DEPOSIT=2, WITHDRAW=4, LOG=8;
    public static final int LEGACY=VIEW|DEPOSIT|WITHDRAW, ALL=LEGACY|LOG;
    public static boolean valid(int mask){return mask>=0&&(mask&~ALL)==0;}
    public static boolean allows(int mask,int required){return valid(mask)&&valid(required)&&(mask&required)==required;}
    public static String describe(int mask){
        if(!valid(mask))return "Invalid permissions (denied)";
        return "View: "+allows(mask,VIEW)+" | Deposit: "+allows(mask,DEPOSIT)+" | Withdraw: "+allows(mask,WITHDRAW)+" | Log: "+allows(mask,LOG);
    }
    private BasePermissions(){}
}
